//
// IronGolem - A Minecraft block logging plugin
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program. If not, see <http://www.gnu.org/licenses/>.
//

package com.intellectualsites.irongolem.inspector;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.intellectualsites.irongolem.IronGolem;
import com.intellectualsites.irongolem.changes.Change;
import com.intellectualsites.irongolem.changes.ChangeQuery;
import com.intellectualsites.irongolem.changes.ChangeSubject;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.tags.CustomItemTagContainer;
import org.bukkit.inventory.meta.tags.ItemTagType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Class used to inspect edits at specific locations
 */
public class Inspector {

    public static final Material INSPECTOR_MATERIAL = Material.DIAMOND_HOE;
    private static final Cache<UUID, Inspector> inspectorCache = CacheBuilder.newBuilder().expireAfterAccess(10, TimeUnit.MINUTES)
        .build();

    private final UUID owner;

    private Inspector(@NotNull final UUID owner) {
        this.owner = owner;
    }

    public ItemStack buildItemStack() {
        final ItemStack itemStack = new ItemStack(INSPECTOR_MATERIAL);
        final ItemMeta meta = itemStack.getItemMeta();
        if (meta == null) {
            throw new IllegalStateException("Failed to create item meta");
        }
        // Get the plugin instance
        final Plugin plugin = JavaPlugin.getPlugin(IronGolem.class);
        final CustomItemTagContainer customItemTagContainer = meta.getCustomTagContainer();
        final NamespacedKey toolKey = new NamespacedKey(plugin, "inspector");
        customItemTagContainer.setCustomTag(toolKey, ItemTagType.STRING, this.owner.toString());
        meta.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + "Inspector");
        itemStack.setItemMeta(meta);
        return itemStack;
    }

    /**
     * Inspect a location
     *
     * @param location Location to inspect
     */
    public void inspectLocation(@NotNull final Location location) {
        ChangeQuery.newQuery().atLocation(location).queryChanges().whenComplete(((changes, throwable) -> {
            final Player player = Bukkit.getPlayer(this.owner);
            if (player == null) {
                return;
            }
            if (throwable != null) {
                // TODO FIX
                throwable.printStackTrace();
                player.sendMessage("something went wrong");
            } else {
                player.sendMessage("changes at that loc");
                for (final Change change : changes) {
                    final ChangeSubject subject = change.getSubject();
                    player.sendMessage(String.format("- %s -> %s at %d", subject.serializeFrom(), subject.serializeTo(), change.getTimestamp()));
                }
            }
        }));
    }

    /**
     * Attempt to grab a {@link Inspector} from a {@link ItemStack}
     *
     * @param itemStack The item stack to get the inspector from
     * @return The inspector, if it could be found
     */
    @Nullable public static Inspector fromItem(@NotNull final Player player, @NotNull final ItemStack itemStack) {
        if (!itemStack.hasItemMeta()) {
            return null;
        }
        if (itemStack.getType() != INSPECTOR_MATERIAL) {
            return null;
        }
        final ItemMeta meta = itemStack.getItemMeta();
        if (meta == null) {
            return null;
        }
        // Get the plugin instance
        final Plugin plugin = JavaPlugin.getPlugin(IronGolem.class);
        final CustomItemTagContainer customItemTagContainer = meta.getCustomTagContainer();
        final NamespacedKey toolKey = new NamespacedKey(plugin, "inspector");
        if (!customItemTagContainer.hasCustomTag(toolKey, ItemTagType.STRING)) {
            return null;
        }
        final String inspectorOwner = customItemTagContainer.getCustomTag(toolKey, ItemTagType.STRING);
        if (!Objects.equals(inspectorOwner, player.getUniqueId().toString())) {
            return null;
        }
        try {
            return inspectorCache.get(player.getUniqueId(), () -> new Inspector(player.getUniqueId()));
        } catch (final ExecutionException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Create a new inspector for a player
     *
     * @param player Player
     * @return Created inspector
     */
    @NotNull public static Inspector createInspector(final Player player) {
        try {
            return inspectorCache.get(player.getUniqueId(), () -> new Inspector(player.getUniqueId()));
        } catch (final ExecutionException e) {
            e.printStackTrace();
        }
        return new Inspector(player.getUniqueId());
    }

}
