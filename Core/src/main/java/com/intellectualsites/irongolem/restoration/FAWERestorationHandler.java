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

package com.intellectualsites.irongolem.restoration;

import com.boydti.fawe.util.EditSessionBuilder;
import com.intellectualsites.irongolem.IronGolem;
import com.intellectualsites.irongolem.changes.BlockSubject;
import com.intellectualsites.irongolem.changes.Change;
import com.intellectualsites.irongolem.changes.ChangeSource;
import com.intellectualsites.irongolem.changes.ChangeSubject;
import com.intellectualsites.irongolem.changes.ChangeType;
import com.intellectualsites.irongolem.changes.Changes;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.entity.BaseEntity;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldedit.world.biome.BiomeType;
import com.sk89q.worldedit.world.biome.BiomeTypes;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import org.bukkit.Bukkit;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public class FAWERestorationHandler implements RestorationHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(FAWERestorationHandler.class);

    private final Object regionLock = new Object();
    private final IronGolem ironGolem;
    private final Collection<com.intellectualsites.irongolem.util.CuboidRegion> regions;

    public FAWERestorationHandler(@NotNull final IronGolem ironGolem) {
        this.ironGolem = ironGolem;
        this.regions = new HashSet<>();
    }

    @Override
    public void restore(@NotNull final Changes changes, @NotNull final ChangeSource source,
        @NotNull final Runnable completionTask) throws RegionLockedException {
        if (!changes.isDistinct()) {
            throw new IllegalArgumentException("Only distinct change sets can be restored to");
        }
        if (!this.createRegionLock(changes.getRegion())) {
            throw new RegionLockedException(changes.getRegion());
        }
        Bukkit.getScheduler().runTaskAsynchronously(ironGolem, () -> {
            final Collection<Change> restorationChanges = changes.getRestorationChangeSet(source);
            try {
                final com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(changes.getWorld());
                final EditSession session =
                    new EditSessionBuilder(weWorld).checkMemory(false).fastmode(true).limitUnlimited().changeSetNull().autoQueue(false).build();

                for (final Change change : changes.getChanges()) {
                    final BlockVector3 location = BukkitAdapter.asBlockVector(change.getLocation());
                    final ChangeSubject<?, ?> subject = change.getSubject();
                    if (subject.getType() != ChangeType.BLOCK) {
                        continue;
                    }
                    final BlockSubject blockSubject = (BlockSubject) subject;
                    final BaseBlock block = BukkitAdapter.adapt(blockSubject.getFrom())
                        .toBaseBlock(); /* TODO: Support NBT */
                    session.setBlock(location, block);
                }

                /*final CuboidRegion region = convertRegion(changes.getRegion());
                final ChangeExtent changeExtent = new ChangeExtent(source, weWorld, region, changes.getChanges());
                final ForwardExtentCopy forwardExtentCopy = new ForwardExtentCopy(changeExtent, region, session, region.getMinimumPoint());
                forwardExtentCopy.setCopyingEntities(false);
                forwardExtentCopy.setCopyingBiomes(false);
                forwardExtentCopy.setRemovingEntities(false);
                try {
                    Operations.complete(forwardExtentCopy);
                } catch (final WorldEditException e) {
                    LOGGER.error("Failed to restore region", e);
                }*/
                session.flushSession();
                // Log the restoration
                ironGolem.getChangeLogger().logChanges(restorationChanges);
                completionTask.run();
            } catch (final Exception e) {
                LOGGER.error("Failed to restore region", e);
            } finally {
                this.freeRegion(changes.getRegion());
            }
        });
    }

    @Override public boolean createRegionLock(@NotNull final com.intellectualsites.irongolem.util.CuboidRegion region) {
        synchronized (this.regionLock) {
            for (final com.intellectualsites.irongolem.util.CuboidRegion lockingRegion : this.regions) {
                if (lockingRegion.intersects(region)) {
                    return false;
                }
            }
            this.regions.add(region);
        }
        return true;
    }

    @Override
    public void freeRegion(@NotNull final com.intellectualsites.irongolem.util.CuboidRegion region) {
        synchronized (this.regionLock) {
            this.regions.remove(region);
        }
    }

    private static CuboidRegion convertRegion(
        @NotNull final com.intellectualsites.irongolem.util.CuboidRegion region) {
        final Vector minPoint = region.getMinimumPoint();
        final Vector maxPoint = region.getMaximumPoint();
        return new CuboidRegion(
            BlockVector3.at(minPoint.getBlockX(), minPoint.getBlockY(), minPoint.getBlockZ()),
            BlockVector3.at(maxPoint.getBlockX(), maxPoint.getBlockY(), maxPoint.getBlockZ()));
    }

    private static final class ChangeExtent implements Extent {

        private final Map<BlockVector3, BaseBlock> changes = new HashMap<>();
        private final CuboidRegion region;
        private final World world;
        private final ChangeSource source;

        private ChangeExtent(final ChangeSource source, final World world,
            final CuboidRegion region, final Collection<Change> changes) {
            this.region = region;
            this.source = source;
            this.world = world;
            for (final Change change : changes) {
                final BlockVector3 location = BukkitAdapter.asBlockVector(change.getLocation());
                final ChangeSubject<?, ?> subject = change.getSubject();
                if (subject.getType() != ChangeType.BLOCK) {
                    continue;
                }
                final BlockSubject blockSubject = (BlockSubject) subject;
                final BaseBlock block = BukkitAdapter.adapt(blockSubject.getFrom())
                    .toBaseBlock(); /* TODO: Support NBT */
                this.changes.put(location, block);
            }
        }

        @Override public BlockVector3 getMinimumPoint() {
            return this.region.getMinimumPoint();
        }

        @Override public BlockVector3 getMaximumPoint() {
            return this.region.getMaximumPoint();
        }

        @Override public List<? extends Entity> getEntities(final Region region) {
            return Collections.emptyList();
        }

        @Override public List<? extends Entity> getEntities() {
            return Collections.emptyList();
        }

        @Nullable @Override public Entity createEntity(Location location, BaseEntity entity) {
            return null;
        }

        @Override public BlockState getBlock(final BlockVector3 position) {
            return this.getFullBlock(position).toImmutableState();
        }

        @Override public BaseBlock getFullBlock(BlockVector3 position) {
            final BaseBlock block = this.changes.get(position);
            if (block != null) {
                return block;
            }
            return world.getFullBlock(position);
        }

        @Override public BiomeType getBiome(BlockVector2 position) {
            return BiomeTypes.OCEAN;
        }

        @Override
        public <T extends BlockStateHolder<T>> boolean setBlock(BlockVector3 position, T block) {
            return false;
        }

        @Override public boolean setBiome(BlockVector2 position, BiomeType biome) {
            return false;
        }

        @Nullable @Override public Operation commit() {
            return null;
        }
    }

}
