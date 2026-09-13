package fr.doctorwho.sgccontrol;

import fr.doctorwho.sgccontrol.network.NetworkHandler;
import fr.doctorwho.sgccontrol.network.OpenConsolePacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class SgcTerminalBlock extends HorizontalDirectionalBlock {
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    private static final VoxelShape SHAPE_NORTH = Shapes.or(
            box(1, 0, 3, 15, 1, 13),
            box(2, 1, 4, 14, 2, 12),
            box(3, 2, 5, 13, 3, 11),
            box(5, 2, 3, 11, 4, 5),
            box(3, 3, 10, 13, 14, 12)
    );
    private static final VoxelShape SHAPE_SOUTH = Shapes.or(
            box(1, 0, 3, 15, 1, 13),
            box(2, 1, 4, 14, 2, 12),
            box(3, 2, 5, 13, 3, 11),
            box(5, 2, 11, 11, 4, 13),
            box(3, 3, 4, 13, 14, 6)
    );
    private static final VoxelShape SHAPE_EAST = Shapes.or(
            box(3, 0, 1, 13, 1, 15),
            box(4, 1, 2, 12, 2, 14),
            box(5, 2, 3, 11, 3, 13),
            box(3, 2, 5, 5, 4, 11),
            box(10, 3, 3, 12, 14, 13)
    );
    private static final VoxelShape SHAPE_WEST = Shapes.or(
            box(3, 0, 1, 13, 1, 15),
            box(4, 1, 2, 12, 2, 14),
            box(5, 2, 3, 11, 3, 13),
            box(11, 2, 5, 13, 4, 11),
            box(4, 3, 3, 6, 14, 13)
    );

    public SgcTerminalBlock(Properties properties) {
        super(properties);
        registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case SOUTH -> SHAPE_SOUTH;
            case EAST -> SHAPE_EAST;
            case WEST -> SHAPE_WEST;
            default -> SHAPE_NORTH;
        };
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (!level.isClientSide && player instanceof ServerPlayer sp) {
            NetworkHandler.sendTo(sp, new OpenConsolePacket(pos));
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
