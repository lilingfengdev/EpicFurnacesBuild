package com.craftaro.epicfurnaces.furnace;

import com.craftaro.core.compatibility.CompatibleMaterial;
import com.craftaro.core.compatibility.ServerVersion;
import com.craftaro.core.database.Data;
import com.craftaro.core.database.SerializedLocation;
import com.craftaro.core.gui.GuiManager;
import com.craftaro.core.hooks.EconomyManager;
import com.craftaro.core.hooks.ProtectionManager;
import com.craftaro.core.math.MathUtils;
import com.craftaro.third_party.com.cryptomorin.xseries.XMaterial;
import com.craftaro.third_party.com.cryptomorin.xseries.XSound;
import com.craftaro.epicfurnaces.EpicFurnaces;
import com.craftaro.epicfurnaces.level.Level;
import com.craftaro.epicfurnaces.settings.Settings;
import com.craftaro.epicfurnaces.boost.BoostData;
import com.craftaro.epicfurnaces.gui.GUIOverview;
import com.craftaro.epicfurnaces.utils.CostType;
import com.craftaro.epicfurnaces.utils.Methods;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.inventory.FurnaceInventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class Furnace implements Data {
    private final EpicFurnaces plugin = EpicFurnaces.getPlugin(EpicFurnaces.class);

    private final String hologramId = UUID.randomUUID().toString();

    // Identifier for database use.
    private int id;

    private final Location location;
    private Level level = this.plugin.getLevelManager().getLowestLevel();
    private String nickname = null;
    private UUID placedBy = null;
    private int uses, radiusOverheatLast, radiusFuelshareLast = 0;

    private final Map<XMaterial, Integer> toLevel = new HashMap<>();

    private final List<Location> radiusOverheat = new ArrayList<>();
    private final List<Location> radiusFuelshare = new ArrayList<>();
    private final List<UUID> accessList = new ArrayList<>();

    /**
     * Constructor for database use.
     */
    public Furnace() {
        this.location = null;
    }

    public Furnace(Location location) {
        this.location = location;
        this.id = this.plugin.getDataManager().getNextId("active_furnaces");
    }

    public Furnace(Map<String, Object> map) {
        this.id = (int) map.get("id");
        this.level = this.plugin.getLevelManager().getLevel((int) map.get("level"));
        this.uses = (int) map.get("uses");
        this.placedBy = map.get("placed_by") == null ? null : UUID.fromString((String) map.get("placed_by"));
        this.nickname = (String) map.get("nickname");
        this.location = SerializedLocation.of(map);
    }

    public void setToLevel(Map<XMaterial, Integer> toLevel) {
        this.toLevel.clear();
        this.toLevel.putAll(toLevel);
    }

    public void overview(GuiManager guiManager, Player player) {
        if (this.placedBy == null) {
            this.placedBy = player.getUniqueId();
        }

        if (!player.hasPermission("epicfurnaces.overview")) {
            return;
        }

        if (Settings.USE_PROTECTION_PLUGINS.getBoolean() && !ProtectionManager.canInteract(player, this.location)) {
            player.sendMessage(this.plugin.getLocale().getMessage("event.general.protected").getPrefixedMessage());
            return;
        }

        guiManager.showGUI(player, new GUIOverview(this.plugin, this, player));
    }

    public void plus(FurnaceSmeltEvent event) {
        Block block = this.location.getBlock();
        if (!block.getType().name().contains("FURNACE") && !block.getType().name().contains("SMOKER")) {
            return;
        }

        uses++;
        plugin.getDataHelper().queueFurnaceForUpdate(this);

        XMaterial material = CompatibleMaterial.getMaterial(event.getResult().getType()).get();
        int needed = -1;

        if (level.getMaterials().containsKey(material)) {
            int amount = addToLevel(material, 1);
            plugin.getDataHelper().updateLevelupItems(this, material, amount);
            needed = level.getMaterials().get(material) - getToLevel(material);
        }

        if (!level.hasReward())
            return;

        if (Settings.UPGRADE_BY_SMELTING.getBoolean() &&
                needed == 0 &&
                this.plugin.getLevelManager().getLevel(this.level.getLevel() + 1) != null) {
            this.toLevel.remove(material);
            levelUp();
        }

        updateCook();

        FurnaceInventory inventory = (FurnaceInventory) ((InventoryHolder) block.getState()).getInventory();

        if (event.getSource().getType().name().contains("SPONGE") ||
                event.getSource().getType().name().contains("COBBLESTONE") ||
                event.getSource().getType().name().contains("DEEPSLATE")) {
            return;
        }

        int percent = level.getRewardPercent();
        double rand = Math.random() * 100;
        if (rand >= percent
                || event.getResult().equals(Material.SPONGE)
                || Settings.NO_REWARDS_FROM_RECIPES.getBoolean()
                && this.plugin.getFurnaceRecipeFile().contains("Recipes." + inventory.getSmelting().getType())) {
            return;
        }

        int randomAmount = level.getRandomReward();

        BoostData boostData = this.plugin.getBoostManager().getBoost(this.placedBy);
        randomAmount = randomAmount * (boostData == null ? 1 : boostData.getMultiplier());

        event.getResult().setAmount(Math.min(event.getResult().getAmount() + randomAmount, event.getResult().getMaxStackSize()));
    }

    public void upgrade(Player player, CostType type) {
        if (!this.plugin.getLevelManager().getLevels().containsKey(this.level.getLevel() + 1)) {
            return;
        }

        Level level = this.plugin.getLevelManager().getLevel(this.level.getLevel() + 1);
        int cost = type == CostType.ECONOMY ? level.getCostEconomy() : level.getCostExperience();

        if (type == CostType.ECONOMY) {
            if (!EconomyManager.isEnabled()) {
                player.sendMessage("Economy not enabled.");
                return;
            }
            if (!EconomyManager.hasBalance(player, cost)) {
                this.plugin.getLocale().getMessage("event.upgrade.cannotafford").sendPrefixedMessage(player);
                return;
            }
            EconomyManager.withdrawBalance(player, cost);
            upgradeFinal(player);
        } else if (type == CostType.EXPERIENCE) {
            if (player.getLevel() >= cost || player.getGameMode() == GameMode.CREATIVE) {
                if (player.getGameMode() != GameMode.CREATIVE) {
                    player.setLevel(player.getLevel() - cost);
                }
                upgradeFinal(player);
            } else {
                this.plugin.getLocale().getMessage("event.upgrade.cannotafford").sendPrefixedMessage(player);
            }
        }
    }

    private void upgradeFinal(Player player) {
        levelUp();
        syncName();
        this.plugin.getDataHelper().queueFurnaceForUpdate(this);
        if (this.plugin.getLevelManager().getHighestLevel() != this.level) {
            this.plugin.getLocale().getMessage("event.upgrade.success")
                    .processPlaceholder("level", this.level.getLevel()).sendPrefixedMessage(player);

        } else {
            this.plugin.getLocale().getMessage("event.upgrade.maxed")
                    .processPlaceholder("level", this.level.getLevel()).sendPrefixedMessage(player);
        }
        Location loc = this.location.clone().add(.5, .5, .5);

        if (!ServerVersion.isServerVersionAtLeast(ServerVersion.V1_12)) {
            return;
        }

        player.getWorld().spawnParticle(org.bukkit.Particle.valueOf(this.plugin.getConfig().getString("Main.Upgrade Particle Type")), loc, 200, .5, .5, .5);

        if (this.plugin.getLevelManager().getHighestLevel() != this.level) {
            XSound.ENTITY_PLAYER_LEVELUP.play(player, .6f, 15);
        } else {
            XSound.ENTITY_PLAYER_LEVELUP.play(player, 2, 25);

            if (!ServerVersion.isServerVersionAtLeast(ServerVersion.V1_13)) {
                return;
            }

            XSound.BLOCK_NOTE_BLOCK_CHIME.play(player, 2, 25);
            Bukkit.getScheduler().scheduleSyncDelayedTask(this.plugin, () -> XSound.BLOCK_NOTE_BLOCK_CHIME.play(player, 1.2f, 35), 5);
            Bukkit.getScheduler().scheduleSyncDelayedTask(this.plugin, () -> XSound.BLOCK_NOTE_BLOCK_CHIME.play(player, 1.8f, 35), 10);
        }
    }

    public void levelUp() {
        this.level = this.plugin.getLevelManager().getLevel(this.level.getLevel() + 1);
    }

    private void syncName() {
        org.bukkit.block.Furnace furnace = (org.bukkit.block.Furnace) this.location.getBlock().getState();
        if (ServerVersion.isServerVersionAtLeast(ServerVersion.V1_10)) {
            furnace.setCustomName(Methods.formatName(this.level.getLevel()));
        }
        furnace.update(true);
    }

    public void updateCook() {
        Block block = this.location.getBlock();
        if (!block.getType().name().contains("FURNACE") && !block.getType().name().contains("SMOKER")) {
            return;
        }

        Bukkit.getServer().getScheduler().scheduleSyncDelayedTask(this.plugin, () -> {
            int num = getPerformanceTotal(block.getType());

            int max = (block.getType().name().contains("BLAST") || block.getType().name().contains("SMOKER") ? 100 : 200);
            if (num >= max) {
                num = max - 1;
            }

            if (num != 0) {
                BlockState bs = (block.getState());
                ((org.bukkit.block.Furnace) bs).setCookTime(Short.parseShort(Integer.toString(num)));
                bs.update();
            }
        }, 1L);
    }


    public Level getLevel() {
        return this.level;
    }


    public List<UUID> getAccessList() {
        return Collections.unmodifiableList(this.accessList);
    }

    public int getPerformanceTotal(Material material) {
        String cap = (material.name().contains("BLAST") || material.name().contains("SMOKER") ? "100" : "200");
        String equation = "(" + this.level.getPerformance() + " / 100) * " + cap;
        return (int) MathUtils.eval(equation);
    }

    public boolean addToAccessList(OfflinePlayer player) {
        return addToAccessList(player.getUniqueId());
    }

    public boolean addToAccessList(UUID uuid) {
        return this.accessList.add(uuid);
    }

    public boolean removeFromAccessList(UUID uuid) {
        return this.accessList.remove(uuid);
    }

    public boolean isOnAccessList(OfflinePlayer player) {
        return this.accessList.contains(player.getUniqueId());
    }

    public void clearAccessList() {
        this.accessList.clear();
    }

    public List<Location> getRadius(boolean overHeat) {
        if (overHeat) {
            return this.radiusOverheat.isEmpty() ? null : Collections.unmodifiableList(this.radiusOverheat);
        } else {
            return this.radiusFuelshare.isEmpty() ? null : Collections.unmodifiableList(this.radiusFuelshare);
        }
    }


    public void addToRadius(Location location, boolean overHeat) {
        if (overHeat) {
            this.radiusOverheat.add(location);
        } else {
            this.radiusFuelshare.add(location);
        }
    }


    public void clearRadius(boolean overHeat) {
        if (overHeat) {
            this.radiusOverheat.clear();
        } else {
            this.radiusFuelshare.clear();
        }
    }


    public int getRadiusLast(boolean overHeat) {
        if (overHeat) {
            return this.radiusOverheatLast;
        } else {
            return this.radiusFuelshareLast;
        }
    }


    public void setRadiusLast(int radiusLast, boolean overHeat) {
        if (overHeat) {
            this.radiusOverheatLast = radiusLast;
        } else {
            this.radiusFuelshareLast = radiusLast;
        }
    }

    public Location getLocation() {
        return this.location.clone();
    }

    public boolean isInLoadedChunk() {
        return this.location != null && this.location.getWorld() != null && this.location.getWorld().isChunkLoaded(((int) this.location.getX()) >> 4, ((int) this.location.getZ()) >> 4);
    }

    public void setLevel(Level level) {
        this.level = level;
    }

    public String getNickname() {
        return this.nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public UUID getPlacedBy() {
        return this.placedBy;
    }

    public void setPlacedBy(UUID placedBy) {
        this.placedBy = placedBy;
    }

    public int getUses() {
        return this.uses;
    }

    public void setUses(int uses) {
        this.uses = uses;
    }

    public int getToLevel(XMaterial material) {
        if (!this.toLevel.containsKey(material)) {
            return 0;
        }
        return this.toLevel.get(material);
    }

    public Map<XMaterial, Integer> getToLevel() {
        return Collections.unmodifiableMap(this.toLevel);
    }

    public int addToLevel(XMaterial material, int amount) {
        if (this.toLevel.containsKey(material)) {
            int newAmount = this.toLevel.get(material) + amount;
            this.toLevel.put(material, newAmount);
            return newAmount;
        }

        this.toLevel.put(material, amount);
        return amount;
    }

    public int getRadiusOverheatLast() {
        return this.radiusOverheatLast;
    }

    public void setRadiusOverheatLast(int radiusOverheatLast) {
        this.radiusOverheatLast = radiusOverheatLast;
    }

    public int getRadiusFuelshareLast() {
        return this.radiusFuelshareLast;
    }

    public void setRadiusFuelshareLast(int radiusFuelshareLast) {
        this.radiusFuelshareLast = radiusFuelshareLast;
    }

    public int getId() {
        return this.id;
    }

    @Override
    public Map<String, Object> serialize() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", this.id);
        map.put("level", this.level.getLevel());
        map.put("uses", this.uses);
        map.put("placed_by", this.placedBy == null ? null : this.placedBy.toString());
        map.put("nickname", this.nickname);
        map.putAll(SerializedLocation.of(this.location));
        return map;
    }

    @Override
    public Data deserialize(Map<String, Object> map) {
        return new Furnace(map);
    }

    @Override
    public String getTableName() {
        return "active_furnaces";
    }

    public void dropItems() {
        FurnaceInventory inventory = (FurnaceInventory) ((InventoryHolder) this.location.getBlock().getState()).getInventory();
        ItemStack fuel = inventory.getFuel();
        ItemStack smelting = inventory.getSmelting();
        ItemStack result = inventory.getResult();

        World world = this.location.getWorld();
        if (world == null) {
            return;
        }

        if (fuel != null) {
            world.dropItemNaturally(this.location, fuel);
        }
        if (smelting != null) {
            world.dropItemNaturally(this.location, smelting);
        }
        if (result != null) {
            world.dropItemNaturally(this.location, result);
        }
    }

    public String getHologramId() {
        return this.hologramId;
    }
}
