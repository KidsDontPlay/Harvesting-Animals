package kdp.haan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropsBlock;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.Direction;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.IPlantable;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(HarvestingAnimals.MOD_ID)
@Mod.EventBusSubscriber
public class HarvestingAnimals {

    public static final String MOD_ID = "haan";
    public static final Logger LOG = LogManager.getLogger(HarvestingAnimals.class);

    private static ForgeConfigSpec.BooleanValue forceHarvest, removeDrops, cheatSeed, insertInventory, spreadCrops;
    private static ForgeConfigSpec.IntValue maxAnimals;
    private static ForgeConfigSpec.ConfigValue<List<? extends ResourceLocation>> blackList, whiteList;

    public HarvestingAnimals() {
        Pair<Object, ForgeConfigSpec> pair = new ForgeConfigSpec.Builder().configure(b -> {
            forceHarvest = b.comment("Animals will harvest crops even if they won't eat them.")
                    .define("forceHarvest", false);
            removeDrops = b.comment("Remaining drops will disappear instead of lying around.")
                    .define("removeDrops", false);
            cheatSeed = b.comment(
                    "Sometimes a crop won't drop a seed to replant itself. If enabled it will replant itself anyway.")
                    .define("cheatSeed", false);
            insertInventory = b.comment("Remaining drops will be inserted into inventories next to the farm.")
                    .define("insertInventory", true);
            spreadCrops = b.comment("Animals will spread crops to attached farmlands.").define("spreadCrops", true);
            maxAnimals = b.comment(
                    "Determines the number of equal animals that can be around an animal (5 blocks range) before they stop breeding.",
                    "-1 means no limit.").defineInRange("maxAnimals", 6, -1, 30);
            blackList = b.comment("List for animals that won't breed automatically.")
                    .defineList("blackList", Collections.emptyList(), r -> r instanceof ResourceLocation);
            whiteList = b.comment("List for animals that will breed automatically.")
                    .defineList("whiteList", Collections.emptyList(), r -> r instanceof ResourceLocation);
            return null;
        });
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, pair.getValue());
    }

    @SubscribeEvent
    public static void eat(LivingEvent.LivingUpdateEvent event) {
        if (event.getEntityLiving() instanceof AnimalEntity && !event.getEntityLiving().world.isRemote && event
                .getEntityLiving().ticksExisted % 15 == 0) {
            AnimalEntity ani = (AnimalEntity) event.getEntityLiving();
            BlockPos current = new BlockPos(ani);
            if (Math.abs(ani.posY - MathHelper.floor(ani.posY)) > .5)
                current = current.up();
            if (validCrop(ani, current) && validLove(ani) && nearPartner(ani, current)) {
                List<ItemStack> lis = breakAndReplant(ani, current);
                ItemStack food = null;
                Iterator<ItemStack> it = lis.iterator();
                while (it.hasNext()) {
                    ItemStack s = it.next();
                    if (ani.isBreedingItem(s)) {
                        food = s;
                        it.remove();
                        break;
                    }
                }
                if (food != null)
                    ani.setInLove(null);
                else
                    moveToNextCrop(ani);
                handleRemainingDrops(lis, ani.world, current);
            } else if (forceHarvest.get() && isMatureCrop(ani.world, current) && !ani.isInLove()) {
                List<ItemStack> lis = breakAndReplant(ani, current);
                handleRemainingDrops(lis, ani.world, current);
                Random ran = new Random();
                if (ran.nextBoolean()) {
                    BlockPos p = current.offset(Direction.byHorizontalIndex(ran.nextInt(4)), ran.nextInt(2) + 1);
                    ani.getNavigator().tryMoveToXYZ(p.getX(), p.getY(), p.getZ(), 1.2);
                }
            }
        }
    }

    private static Direction[] HORIZONTALS = Arrays.stream(Direction.values()).filter(d -> d.getAxis().isHorizontal())
            .toArray(Direction[]::new);

    private static void handleRemainingDrops(List<ItemStack> lis, World world, BlockPos pos) {
        if (!removeDrops.get()) {
            if (spreadCrops.get()) {
                List<Direction> faces = Lists.newArrayList(HORIZONTALS);
                Collections.shuffle(faces);
                for (Direction face : faces) {
                    BlockPos neighbor = pos.offset(face);
                    if (!world.isAirBlock(neighbor))
                        continue;
                    Iterator<ItemStack> it = lis.iterator();
                    while (it.hasNext()) {
                        ItemStack k = it.next();
                        if (k.getItem() instanceof IPlantable) {
                            IPlantable plant = (IPlantable) k.getItem();
                            if (world.getBlockState(neighbor.down()).getBlock()
                                    .canSustainPlant(world.getBlockState(neighbor.down()), world, neighbor.down(),
                                            Direction.UP, plant)) {
                                world.setBlockState(neighbor, plant.getPlant(world, pos));
                                it.remove();
                            }
                        }
                    }
                }
            }
            if (insertInventory.get()) {
                ArrayDeque<BlockPos> research = new ArrayDeque<>(Collections.singleton(pos));
                Set<BlockPos> done = Sets.newHashSet();
                Map<IItemHandler, BlockPos> invs = new IdentityHashMap<>();
                while (!research.isEmpty()) {
                    BlockPos current = research.poll();
                    for (Direction facing : HORIZONTALS) {
                        BlockPos searchPos = current.offset(facing);
                        if (!world.isBlockLoaded(searchPos))
                            continue;
                        if (world.getBlockState(searchPos).getBlock() instanceof CropsBlock || world
                                .getBlockState(searchPos.down()).getBlock() == Blocks.FARMLAND || world
                                .getBlockState(searchPos.down()).getBlock()
                                .isFertile(world.getBlockState(searchPos.down()), world, searchPos.down())) {
                            if (!done.contains(searchPos)) {
                                done.add(searchPos);
                                research.add(searchPos);
                            }
                        } else if (world.getTileEntity(searchPos) != null) {
                            TileEntity t = world.getTileEntity(searchPos);
                            t.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, facing.getOpposite())
                                    .ifPresent(inv -> invs.put(inv, searchPos));
                        }
                    }
                }
                for (IItemHandler handler : invs.keySet())
                    for (int i = 0; i < lis.size(); i++) {
                        ItemStack s = lis.get(i);
                        if (s == null)
                            continue;
                        ItemStack rem = ItemHandlerHelper.insertItemStacked(handler, s, false);
                        lis.set(i, rem);
                    }
            }

            for (ItemStack s : lis)
                Block.spawnAsEntity(world, pos, s);
        }
    }

    private static List<ItemStack> breakAndReplant(AnimalEntity ani, BlockPos pos) {
        BlockState state = ani.world.getBlockState(pos);
        CropsBlock crop = (CropsBlock) state.getBlock();
        List<ItemStack> drops = new ArrayList<>(
                Block.getDrops(state, (ServerWorld) ani.world, pos, null, ani, ItemStack.EMPTY));
        BlockState neww = cheatSeed.get() ? state.getBlock().getDefaultState() : Blocks.AIR.getDefaultState();
        Iterator<ItemStack> it = drops.iterator();
        boolean changed = false;
        while (it.hasNext()) {
            ItemStack s = it.next();
            if (s.getItem() instanceof IPlantable) {
                IPlantable plant = (IPlantable) s.getItem();
                if (/*plant.getPlantType(world, pos) == EnumPlantType.Crop && */plant
                        .getPlant(ani.world, pos) != null && plant.getPlant(ani.world, pos).getBlock() == crop) {
                    neww = plant.getPlant(ani.world, pos);
                    it.remove();
                    changed = true;
                    break;
                }
            }
        }
        if (cheatSeed.get() && !changed && !drops.isEmpty())
            drops.remove(0);
        ani.world.setBlockState(pos, neww);
        return drops;
    }

    @SubscribeEvent
    public static void walk(LivingEvent.LivingUpdateEvent event) {
        if (event.getEntityLiving() instanceof AnimalEntity && !event.getEntityLiving().world.isRemote && event
                .getEntityLiving().ticksExisted % 60 == 0) {
            AnimalEntity ani = (AnimalEntity) event.getEntityLiving();
            moveToNextCrop(ani);
        }
    }

    private static void moveToNextCrop(AnimalEntity ani) {
        BlockPos entPos = new BlockPos(ani);
        List<BlockPos> posList = BlockPos.getAllInBox(entPos.add(-7, -2, -7), entPos.add(7, 2, 7)).//
                filter(p -> validCrop(ani, p) && validLove(ani)).//
                sorted(Comparator.comparingDouble(pos -> pos.distanceSq(entPos))).
                collect(Collectors.toList());
        boolean walk = false;
        for (BlockPos p : posList) {
            if (ani.getNavigator().getPathToPos(p, 0) == null || !nearPartner(ani, p))
                continue;
            ani.getNavigator().tryMoveToXYZ(p.getX(), p.getY(), p.getZ(), 1.2);
            walk = true;
        }
        if (forceHarvest.get() && !walk && !ani.isInLove()) {
            List<BlockPos> posList2 = BlockPos.getAllInBox(entPos.add(-7, -2, -7), entPos.add(7, 2, 7)).//
                    filter(p -> isMatureCrop(ani.world, p)).//
                    sorted(Comparator.comparingDouble(pos -> pos.distanceSq(entPos))).
                    collect(Collectors.toList());
            for (BlockPos p : posList2) {
                if (ani.getNavigator().getPathToPos(p, 0) == null)
                    continue;
                ani.getNavigator().tryMoveToXYZ(p.getX(), p.getY(), p.getZ(), 1.2);
            }
        }
    }

    private static int nearSiblings(AnimalEntity ani, double range) {
        return ani.world.getEntitiesWithinAABB(ani.getClass(), new AxisAlignedBB(new BlockPos(ani)).grow(range)).//
                stream().//
                filter(ea -> ea.getClass() == ani.getClass() && ea != ani && !ea.isChild()).//
                collect(Collectors.toList()).size();
    }

    private static boolean nearPartner(AnimalEntity ani, BlockPos crop) {
        return ani.world.getEntitiesWithinAABB(ani.getClass(), new AxisAlignedBB(crop).grow(8.0D)).//
                stream().//
                anyMatch(ea -> ea.isAlive() && ea.getClass() == ani.getClass() && ea != ani && ani.getNavigator()
                .getPathToEntityLiving(ea, 0) != null && (ea.getGrowingAge() == 0 || ea.isInLove()));
    }

    private static Cache<Class<? extends AnimalEntity>, Cache<BlockState, Boolean>> validCropCache = CacheBuilder
            .newBuilder().build();

    private static boolean validCrop(AnimalEntity ani, BlockPos pos) {
        BlockState state = ani.world.getBlockState(pos);
        try {
            return isMatureCrop(ani.world, pos) && validCropCache
                    .get(ani.getClass(), () -> CacheBuilder.newBuilder().build()).get(state,
                            () -> IntStream.range(0, 50).boxed().flatMap(
                                    i -> Block.getDrops(state, (ServerWorld) ani.world, pos, null, ani, ItemStack.EMPTY)
                                            .stream())
                                    .anyMatch(stack -> !stack.isEmpty() && ani.isBreedingItem(stack)));
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean isMatureCrop(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.getBlock() instanceof CropsBlock && ((CropsBlock) state.getBlock()).isMaxAge(state);
    }

    private static boolean validLove(AnimalEntity ani) {
        return ani.isAlive() && animalAllowed(ani) && ani.getGrowingAge() == 0 && !ani.isInLove() && (maxAnimals
                .get() < 0 || nearSiblings(ani, 5.5) <= maxAnimals.get());
    }

    private static boolean animalAllowed(AnimalEntity ani) {
        if (blackList.get().isEmpty() && whiteList.get().isEmpty())
            return true;
        ResourceLocation name = ani.getType().getRegistryName();
        if (name == null)
            return false;
        if (!blackList.get().isEmpty()) {
            return !blackList.get().contains(name);
        } else {
            return whiteList.get().contains(name);
        }
    }

}
