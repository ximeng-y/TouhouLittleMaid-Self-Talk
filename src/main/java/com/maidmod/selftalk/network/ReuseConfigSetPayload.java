package com.maidmod.selftalk.network;

import com.maidmod.selftalk.MaidSelfTalkMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * C2S：提交预制复用设置的<b>目标绝对操作码</b>（不是「切换一次」命令：丢包／乱序时
 * 不会把设置切到意料之外的状态，客户端也无需依赖本地推断链条）。
 * <p>
 * 包内不携带 player UUID：服务端一律用连接上的 {@code ServerPlayer} 身份读写。
 * <p>
 * 操作码（0~4 为原有复用开关，5~10 为 issue #108 新增匹配参数）：
 * <ul>
 *   <li>0 全局关、1 全局开：女仆维度必须为空（防歧义包，携带即拒写）；</li>
 *   <li>2 恢复继承（删除覆盖条目）、3 单只关、4 单只开：女仆 UUID 必须非空，
 *       且须通过归属校验（仅允许操作自己拥有的女仆）；</li>
 *   <li>5 设置跟随服务器（{@code value} 仅 0／1）、6 个人最低条数、7 个人概率：
 *       女仆维度必须为空；个人数值还要求玩家当前已取消跟随；</li>
 *   <li>8 服务器默认最低条数、9 服务器默认概率、10 是否允许个人参数（{@code value} 仅 0／1）：
 *       女仆维度必须为空，且每次重新校验管理员身份。</li>
 * </ul>
 * 新增操作按<b>单字段</b>发送绝对目标值，避免失焦提交时顺带覆盖其他字段，
 * 也避免两个管理员编辑不同字段时互相覆盖整份旧快照。
 * 两种显式布尔都占单只覆盖条目容量；删除覆盖（恢复继承）不受容量限制。
 */
public record ReuseConfigSetPayload(UUID sessionId, long seq, UUID maidUuid, int opcode, int value)
        implements CustomPacketPayload {

    /** 全局关 */
    public static final int OP_GLOBAL_OFF = 0;
    /** 全局开 */
    public static final int OP_GLOBAL_ON = 1;
    /** 单只恢复继承（删除覆盖条目） */
    public static final int OP_MAID_INHERIT = 2;
    /** 单只显式关 */
    public static final int OP_MAID_OFF = 3;
    /** 单只显式开 */
    public static final int OP_MAID_ON = 4;
    /** 设置跟随服务器（value 0 = 取消跟随，1 = 跟随） */
    public static final int OP_FOLLOW_SERVER = 5;
    /** 设置个人最低可用条数 */
    public static final int OP_PLAYER_MIN_ELIGIBLE = 6;
    /** 设置个人使用概率 */
    public static final int OP_PLAYER_HIT_CHANCE = 7;
    /** 设置服务器默认最低可用条数（仅管理员） */
    public static final int OP_SERVER_MIN_ELIGIBLE = 8;
    /** 设置服务器默认使用概率（仅管理员） */
    public static final int OP_SERVER_HIT_CHANCE = 9;
    /** 设置是否允许玩家自定义参数（仅管理员；value 0／1） */
    public static final int OP_PLAYER_PARAMETERS_ALLOWED = 10;

    public static final Type<ReuseConfigSetPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MaidSelfTalkMod.MODID,
                    "reuse_config_set"));

    public static final StreamCodec<FriendlyByteBuf, ReuseConfigSetPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ReuseConfigRequestPayload.NULLABLE_UUID_STREAM_CODEC, ReuseConfigSetPayload::sessionId,
                    ByteBufCodecs.VAR_LONG, ReuseConfigSetPayload::seq,
                    ReuseConfigRequestPayload.NULLABLE_UUID_STREAM_CODEC, ReuseConfigSetPayload::maidUuid,
                    ByteBufCodecs.VAR_INT, ReuseConfigSetPayload::opcode,
                    ByteBufCodecs.VAR_INT, ReuseConfigSetPayload::value,
                    ReuseConfigSetPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
