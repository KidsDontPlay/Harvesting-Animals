package mrriegel.ceanimals;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.block.BlockCrops;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraftforge.common.EnumPlantType;
import net.minecraftforge.common.IPlantable;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.event.entity.living.LivingEvent.LivingUpdateEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.Instance;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import com.google.common.collect.Lists;

@Mod(modid = CropEatingAnimals.MODID, name = CropEatingAnimals.NAME, version = CropEatingAnimals.VERSION, acceptableRemoteVersions = "*")
@EventBusSubscriber
public class CropEatingAnimals {

	@Instance(CropEatingAnimals.MODID)
	public static CropEatingAnimals INSTANCE;

	public static final String VERSION = "1.0.0";
	public static final String NAME = "Crop-Eating Animals";
	public static final String MODID = "ceanimals";

	//config
	public static Configuration config;

	@Mod.EventHandler
	public void preInit(FMLPreInitializationEvent event) {
		//		config = new Configuration(event.getSuggestedConfigurationFile());
		//		if (config.hasChanged())
		//			config.save();
	}

	@SubscribeEvent
	public static void eat(LivingUpdateEvent event) {
		if (event.getEntityLiving() instanceof EntityAnimal && !event.getEntityLiving().worldObj.isRemote && event.getEntityLiving().ticksExisted % 15 == 0) {
			EntityAnimal ani = (EntityAnimal) event.getEntityLiving();
			BlockPos current = new BlockPos(ani);
			if (Math.abs(ani.posY - MathHelper.floor_double(ani.posY)) > .5)
				current = current.up();
			if (validCrop(ani, current) && validLove(ani) && nearPartner(ani)) {
				List<ItemStack> lis = breakAndReplant(ani.worldObj, current);
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
				for (ItemStack s : lis)
					Block.spawnAsEntity(ani.worldObj, current, s);
			}
		}
	}

	private static List<ItemStack> breakAndReplant(World world, BlockPos pos) {
		IBlockState state = world.getBlockState(pos);
		BlockCrops crop = (BlockCrops) state.getBlock();
		List<ItemStack> drops = Lists.newArrayList(crop.getDrops(world, pos, state, 0));
		drops.removeAll(Collections.singleton(null));
		IBlockState neww = null;
		Iterator<ItemStack> it = drops.iterator();
		while (it.hasNext()) {
			ItemStack s = it.next();
			if (s.getItem() instanceof IPlantable) {
				IPlantable plant = (IPlantable) s.getItem();
				if (plant.getPlantType(world, pos) == EnumPlantType.Crop && plant.getPlant(world, pos).getBlock() == crop) {
					neww = plant.getPlant(world, pos);
					it.remove();
					break;
				}
			}
		}
		if (neww != null)
			world.setBlockState(pos, neww);
		return drops;
	}

	@SubscribeEvent
	public static void walk(LivingUpdateEvent event) {
		if (event.getEntityLiving() instanceof EntityAnimal && !event.getEntityLiving().worldObj.isRemote && event.getEntityLiving().ticksExisted % 80 == 0) {
			EntityAnimal ani = (EntityAnimal) event.getEntityLiving();
			if (!nearPartner(ani))
				return;
			List<BlockPos> posList = Lists.newLinkedList();
			BlockPos entPos = new BlockPos(ani);
			for (int y = entPos.getY() - 2; y <= entPos.getY() + 2; y++)
				for (int x = entPos.getX() - 7; x <= entPos.getX() + 7; x++)
					for (int z = entPos.getZ() - 7; z <= entPos.getZ() + 7; z++) {
						BlockPos p = new BlockPos(x, y, z);
						if (validCrop(ani, p) && validLove(ani))
							posList.add(p);
					}
			posList.sort((pos1, pos2) -> Double.compare(pos1.distanceSq(entPos), pos2.distanceSq(entPos)));
			for (BlockPos p : posList) {
				if (ani.getNavigator().getPathToPos(new BlockPos(p.getX(), p.getY(), p.getZ())) == null)
					continue;
				ani.getNavigator().tryMoveToXYZ(p.getX(), p.getY(), p.getZ(), 1.3);
			}
		}
	}

	private static boolean nearPartner(EntityAnimal ani) {
		return ani.worldObj.getEntitiesWithinAABB(ani.getClass(), ani.getEntityBoundingBox().expandXyz(8.0D)).stream().anyMatch(ea -> ea.getClass() == ani.getClass() && ea != ani && (ea.getGrowingAge() == 0 || ea.isInLove()));
	}

	private static boolean validCrop(EntityAnimal ani, BlockPos pos) {
		IBlockState state = ani.worldObj.getBlockState(pos);
		return state.getBlock() instanceof BlockCrops && ((BlockCrops) state.getBlock()).isMaxAge(state) && state.getBlock().getDrops(ani.worldObj, pos, state, 0).stream().anyMatch(stack -> stack != null && ani.isBreedingItem(stack));
	}

	private static boolean validLove(EntityAnimal ani) {
		return ani.getGrowingAge() == 0 && !ani.isInLove();
	}
}
