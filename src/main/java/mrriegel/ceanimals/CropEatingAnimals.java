package mrriegel.ceanimals;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import net.minecraft.block.Block;
import net.minecraft.block.BlockCrops;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraftforge.common.IPlantable;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.event.entity.living.LivingEvent.LivingUpdateEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.Instance;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;

@Mod(modid = CropEatingAnimals.MODID, name = CropEatingAnimals.NAME, version = CropEatingAnimals.VERSION, acceptableRemoteVersions = "*")
@EventBusSubscriber
public class CropEatingAnimals {

	@Instance(CropEatingAnimals.MODID)
	public static CropEatingAnimals INSTANCE;

	public static final String VERSION = "1.4.1";
	public static final String NAME = "Crop-Eating Animals";
	public static final String MODID = "ceanimals";

	// config
	public static Configuration config;
	public static boolean forceHarvest, removeDrops, cheatSeed, insertInventory, spreadCrops, noReplant;
	public static int maxAnimals;
	public static List<ResourceLocation> blackList, whiteList;

	@Mod.EventHandler
	public void preInit(FMLPreInitializationEvent event) {
		config = new Configuration(event.getSuggestedConfigurationFile());
		forceHarvest = config.getBoolean("forceHarvest", Configuration.CATEGORY_GENERAL, false, "Animals will harvest crops even if they won't eat them.");
		removeDrops = config.getBoolean("removeDrops", Configuration.CATEGORY_GENERAL, false, "Remaining drops will disappear instead of lying around.");
		cheatSeed = config.getBoolean("cheatSeed", Configuration.CATEGORY_GENERAL, false, "Sometimes a crop won't drop a seed to replant itself. If enabled it will replant itself anyway.");
		insertInventory = config.getBoolean("insertInventory", Configuration.CATEGORY_GENERAL, true, "Remaining drops will be inserted into inventories next to the farm.");
		spreadCrops = config.getBoolean("spreadCrops", Configuration.CATEGORY_GENERAL, true, "Animals will spread crops to attached farmlands.");
		noReplant = config.getBoolean("noReplant", Configuration.CATEGORY_GENERAL, false, "Seed won't be replanted automatically.");
		maxAnimals = config.getInt("maxAnimals", Configuration.CATEGORY_GENERAL, 6, -1, 30, "Determines the number of equal animals that can be around an animal (5 blocks range) before they stop breeding." + Configuration.NEW_LINE + "-1 means no limit.");
		Function<String, ResourceLocation> f = s -> new ResourceLocation(s);
		blackList = Lists.newArrayList(config.getStringList("blackList", "List", new String[] { "bat", "slime", "zombie_pigman" }, "List for animals that won't breed automatically.")).stream().map(f).collect(Collectors.toList());
		whiteList = Lists.newArrayList(config.getStringList("whiteList", "List", new String[] {}, "List for animals that will breed automatically.")).stream().map(f).collect(Collectors.toList());
		if (!blackList.isEmpty() && !whiteList.isEmpty())
			throw new IllegalStateException("At least one of the lists have to be empty.");
		if (config.hasChanged())
			config.save();
	}

	@SubscribeEvent
	public static void eat(LivingUpdateEvent event) {
		if (event.getEntityLiving() instanceof EntityAnimal && !event.getEntityLiving().world.isRemote && event.getEntityLiving().ticksExisted % 15 == 0) {
			EntityAnimal ani = (EntityAnimal) event.getEntityLiving();
			BlockPos current = new BlockPos(ani);
			if (Math.abs(ani.posY - MathHelper.floor(ani.posY)) > .5)
				current = current.up();
			if (validCrop(ani, current) && validLove(ani) && nearPartner(ani, current)) {
				List<ItemStack> lis = breakAndReplant(ani.world, current);
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
			} else if (CropEatingAnimals.forceHarvest && isMatureCrop(ani.world, current) && !ani.isInLove()) {
				List<ItemStack> lis = breakAndReplant(ani.world, current);
				handleRemainingDrops(lis, ani.world, current);
				Random ran = new Random();
				if (ran.nextBoolean()) {
					BlockPos p = current.offset(EnumFacing.VALUES[ran.nextInt(6)], ran.nextInt(2) + 1);
					ani.getNavigator().tryMoveToXYZ(p.getX(), p.getY(), p.getZ(), 1.2);
				}
			}
		}
	}

	private static void handleRemainingDrops(List<ItemStack> lis, World world, BlockPos pos) {
		if (!CropEatingAnimals.removeDrops) {
			if (CropEatingAnimals.spreadCrops) {
				List<EnumFacing> faces = Lists.newArrayList(EnumFacing.HORIZONTALS);
				Collections.shuffle(faces);
				for (EnumFacing face : faces) {
					BlockPos neighbor = pos.offset(face);
					if (!world.isAirBlock(neighbor))
						continue;
					Iterator<ItemStack> it = lis.iterator();
					while (it.hasNext()) {
						ItemStack k = it.next();
						if (k.getItem() instanceof IPlantable) {
							IPlantable plant = (IPlantable) k.getItem();
							if (world.getBlockState(neighbor.down()).getBlock().canSustainPlant(world.getBlockState(neighbor.down()), world, neighbor.down(), EnumFacing.UP, plant)) {
								world.setBlockState(neighbor, plant.getPlant(world, pos));
								it.remove();
							}
						}
					}
				}
			}
			if (CropEatingAnimals.insertInventory) {
				LinkedList<BlockPos> research = Lists.newLinkedList(Collections.singleton(pos));
				Set<BlockPos> done = Sets.newHashSet();
				Map<IItemHandler, BlockPos> invs = Maps.newHashMap();
				while (!research.isEmpty()) {
					BlockPos current = research.poll();
					for (EnumFacing facing : EnumFacing.HORIZONTALS) {
						BlockPos searchPos = current.offset(facing);
						if (!world.isBlockLoaded(searchPos))
							continue;
						if (world.getBlockState(searchPos).getBlock() instanceof BlockCrops || world.getBlockState(searchPos.down()).getBlock() == Blocks.FARMLAND || world.getBlockState(searchPos.down()).getBlock().isFertile(world, searchPos.down())) {
							if (!done.contains(searchPos)) {
								done.add(searchPos);
								research.add(searchPos);
							}
						} else if (world.getTileEntity(searchPos) != null) {
							TileEntity t = world.getTileEntity(searchPos);
							if (t.hasCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, facing.getOpposite()))
								invs.put(t.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, facing.getOpposite()), searchPos);
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

	private static List<ItemStack> breakAndReplant(World world, BlockPos pos) {
		IBlockState state = world.getBlockState(pos);
		BlockCrops crop = (BlockCrops) state.getBlock();
		List<ItemStack> drops = Lists.newLinkedList(crop.getDrops(world, pos, state, 0));
		if (CropEatingAnimals.noReplant) {
			world.destroyBlock(pos, false);
		} else {
			IBlockState neww = CropEatingAnimals.cheatSeed ? state.getBlock().getDefaultState() : Blocks.AIR.getDefaultState();
			Iterator<ItemStack> it = drops.iterator();
			boolean changed = false;
			while (it.hasNext()) {
				ItemStack s = it.next();
				if (s.getItem() instanceof IPlantable) {
					IPlantable plant = (IPlantable) s.getItem();
					if ( //plant.getPlantType(world, pos) == EnumPlantType.Crop &&
					plant.getPlant(world, pos) != null && plant.getPlant(world, pos).getBlock() == crop) {
						neww = plant.getPlant(world, pos);
						it.remove();
						changed = true;
						break;
					}
				}
			}
			if (CropEatingAnimals.cheatSeed && !changed && !drops.isEmpty())
				drops.remove(0);
			world.setBlockState(pos, neww);
		}
		return drops;
	}

	@SubscribeEvent
	public static void walk(LivingUpdateEvent event) {
		if (event.getEntityLiving() instanceof EntityAnimal && !event.getEntityLiving().world.isRemote && event.getEntityLiving().ticksExisted % (60) == 0) {
			EntityAnimal ani = (EntityAnimal) event.getEntityLiving();
			moveToNextCrop(ani);
		}
	}

	private static void moveToNextCrop(EntityAnimal ani) {
		BlockPos entPos = new BlockPos(ani);
		List<BlockPos> posList = Lists.newLinkedList(BlockPos.getAllInBox(entPos.add(-7, -2, -7), entPos.add(7, 2, 7))).//
				stream().//
				filter(p -> validCrop(ani, p) && validLove(ani)).//
				collect(Collectors.toList());
		posList.sort((pos1, pos2) -> Double.compare(pos2.distanceSq(entPos), pos1.distanceSq(entPos)));
		boolean walk = false;
		for (BlockPos p : posList) {
			if (ani.getNavigator().getPathToPos(p) == null || !nearPartner(ani, p))
				continue;
			ani.getNavigator().tryMoveToXYZ(p.getX(), p.getY(), p.getZ(), 1.2);
			walk = true;
		}
		if (CropEatingAnimals.forceHarvest && !walk && !ani.isInLove()) {
			List<BlockPos> posList2 = Lists.newLinkedList(BlockPos.getAllInBox(entPos.add(-7, -2, -7), entPos.add(7, 2, 7))).//
					stream().//
					filter(p -> isMatureCrop(ani.world, p)).//
					collect(Collectors.toList());
			posList2.sort((pos1, pos2) -> Double.compare(pos1.distanceSq(entPos), pos2.distanceSq(entPos)));
			for (BlockPos p : posList2) {
				if (ani.getNavigator().getPathToPos(p) == null)
					continue;
				ani.getNavigator().tryMoveToXYZ(p.getX(), p.getY(), p.getZ(), 1.2);
			}
		}
	}

	private static int nearSiblings(EntityAnimal ani, double range) {
		return ani.world.getEntitiesWithinAABB(ani.getClass(), new AxisAlignedBB(new BlockPos(ani)).grow(range)).//
				stream().//
				filter(ea -> ea.getClass() == ani.getClass() && ea != ani && !ea.isChild()).//
				collect(Collectors.toList()).size();
	}

	private static boolean nearPartner(EntityAnimal ani, BlockPos crop) {
		return ani.world.getEntitiesWithinAABB(ani.getClass(), new AxisAlignedBB(crop).grow(8.0D)).//
				stream().//
				anyMatch(ea -> !ea.isDead && ea.getClass() == ani.getClass() && ea != ani && ani.getNavigator().getPathToEntityLiving(ea) != null && (ea.getGrowingAge() == 0 || ea.isInLove()));
	}

	private static boolean validCrop(EntityAnimal ani, BlockPos pos) {
		IBlockState state = ani.world.getBlockState(pos);
		return isMatureCrop(ani.world, pos) && state.getBlock().getDrops(ani.world, pos, state, 0).//
				stream().//
				anyMatch(stack -> stack != null && ani.isBreedingItem(stack));
	}

	private static boolean isMatureCrop(World world, BlockPos pos) {
		IBlockState state = world.getBlockState(pos);
		return state.getBlock() instanceof BlockCrops && ((BlockCrops) state.getBlock()).isMaxAge(state);
	}

	private static boolean validLove(EntityAnimal ani) {
		return !ani.isDead && animalAllowed(ani) && ani.getGrowingAge() == 0 && !ani.isInLove() && (CropEatingAnimals.maxAnimals < 0 || nearSiblings(ani, 5.5) <= CropEatingAnimals.maxAnimals);
	}

	private static boolean animalAllowed(EntityAnimal ani) {
		if (blackList.isEmpty() && whiteList.isEmpty())
			return true;
		ResourceLocation name = EntityList.getKey(ani);
		if (name == null)
			return false;
		if (!blackList.isEmpty()) {
			return !blackList.contains(name);
		} else {
			return whiteList.contains(name);
		}
	}
}
