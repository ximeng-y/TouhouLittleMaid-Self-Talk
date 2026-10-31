package com.maidmod.selftalk;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** 玩家请求登记：准备时不计数，派发返回后激活，结束信号只接收一次。 */
public final class PlayerChatRequest {
    private final Registry owner;
    private final AtomicBoolean ended = new AtomicBoolean();
    /** 服务端主线程字段，与是否仍在途分开，保证快速结束也保留派发副作用。 */
    private boolean dispatched;

    public PlayerChatRequest(Registry owner) {
        this.owner = owner;
    }

    public Registry owner() {
        return owner;
    }

    /** 响应线程只标记结束；登记表的增删仍由服务端主线程执行。 */
    public boolean signalEnd() {
        return ended.compareAndSet(false, true);
    }

    public boolean dispatched() {
        return dispatched;
    }

    /** 正常派发返回后调用；同步抛异常没有此步骤。返回是否首次完成派发。 */
    public boolean dispatchReturned(long tick) {
        if (dispatched || owner.retired) {
            return false;
        }
        dispatched = true;
        if (!ended.get()) {
            owner.active.add(this);
            owner.sinceTick = tick;
        }
        return true;
    }

    /** 只移除自身成员资格；未登记、重复或迟到释放不影响其他请求。 */
    public boolean release() {
        if (!owner.active.remove(this)) {
            return false;
        }
        if (owner.active.isEmpty()) {
            owner.sinceTick = -1;
        }
        return true;
    }

    /** 每个 State 独占一份登记表，其对象身份也用于辨别移除后重建的新 State。 */
    public static final class Registry {
        private final Set<PlayerChatRequest> active =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private long sinceTick = -1;
        private boolean retired;

        public int count() {
            return active.size();
        }

        public long sinceTick() {
            return sinceTick;
        }

        /** 沿用既有五分钟超时口径，只退役这一批登记，不取消 HTTP 或抑制原生回复。 */
        public void reset() {
            for (PlayerChatRequest request : active) {
                request.signalEnd();
            }
            active.clear();
            sinceTick = -1;
        }

        /** State 移除或停服后，准备中的旧请求也不得再激活。 */
        public void retire() {
            retired = true;
            reset();
        }
    }
}
