package com.stellaris.initialize.impl.composite;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

/**
 * 组合校验节点的抽象父类。
 *
 * 项目中的每一个具体校验器都需要继承该类，例如：
 *
 * 1. 创建订单根节点；
 * 2. 节目校验节点；
 * 3. 票档校验节点；
 * 4. 购票数量校验节点；
 * 5. 购票人校验节点。
 *
 * 每一个对象既可以：
 * 1. 执行自己的业务校验；
 * 2. 保存自己的子校验节点。
 *
 * 因此多个校验节点可以组成一棵树。
 *
 * @param <T> 执行校验时传入的参数类型，
 *            例如创建订单时可能是 ProgramOrderCreateDto
 */
public abstract class AbstractComposite<T> {

    /**
     * 当前节点下面的所有直接子节点。
     *
     * 例如：
     *
     * 创建订单根节点
     * ├── 节目校验节点
     * └── 购票人校验节点
     *
     * 那么根节点的 list 中保存的就是：
     *
     * [节目校验节点, 购票人校验节点]
     */
    protected List<AbstractComposite<T>> list = new ArrayList<>();

    /**
     * 执行当前节点自己的业务逻辑。
     *
     * 具体校验内容由子类实现。
     *
     * 例如：
     * - 校验节目是否存在；
     * - 校验票档是否存在；
     * - 校验购票数量是否合法；
     * - 校验购票人信息是否完整。
     *
     * 如果校验失败，子类通常会直接抛出业务异常；
     * 如果没有抛出异常，就表示当前节点校验通过。
     *
     * @param param 本次业务请求参数
     */
    protected abstract void execute(T param);

    /**
     * 返回当前节点所属的业务校验类型。
     *
     * 用于将多个节点归入同一棵校验树。
     *
     * 例如创建订单相关节点都返回：
     *
     * PROGRAM_ORDER_CREATE_CHECK
     *
     * 取消订单相关节点可能返回：
     *
     * PROGRAM_ORDER_CANCEL_CHECK
     *
     * @return 当前节点所属的业务校验类型
     */
    public abstract String type();

    /**
     * 返回当前节点父节点的 executeOrder。
     *
     * 容器构建树时，会根据这个值寻找当前节点的父节点。
     *
     * 例如：
     *
     * 根节点：
     * executeTier()       = 1
     * executeOrder()      = 1
     * executeParentOrder()= 0
     *
     * 节目校验节点：
     * executeTier()       = 2
     * executeOrder()      = 1
     * executeParentOrder()= 1
     *
     * 表示“节目校验节点”的父节点，
     * 是上一层中 executeOrder 等于 1 的节点。
     *
     * 根节点没有父节点，因此通常返回 0 或 null。
     *
     * @return 父节点在上一层中的编号
     */
    public abstract Integer executeParentOrder();

    /**
     * 返回当前节点位于校验树的第几层。
     *
     * 例如：
     *
     * 第 1 层：创建订单根节点
     * 第 2 层：节目校验、购票人校验
     * 第 3 层：票档校验、购票数量校验
     *
     * @return 当前节点所在层级
     */
    public abstract Integer executeTier();

    /**
     * 返回当前节点在本层中的编号。
     *
     * 该编号主要有两个作用：
     *
     * 1. 在同一层中标识当前节点；
     * 2. 供下一层节点通过 executeParentOrder() 寻找父节点。
     *
     * 注意：
     * 从目前 CompositeContainer 的实现来看，
     * 它主要承担“节点编号”的作用，
     * 不一定真正保证同层节点严格按照该值执行。
     *
     * @return 当前节点在本层中的编号
     */
    public abstract Integer executeOrder();

    /**
     * 将一个节点添加为当前节点的子节点。
     *
     * 构建校验树时，CompositeContainer 会调用该方法，
     * 把下一层节点挂到对应父节点下面。
     *
     * 例如：
     *
     * root.add(programCheck);
     *
     * 构建后：
     *
     * root
     * └── programCheck
     *
     * @param abstractComposite 要添加的子节点
     */
    public void add(AbstractComposite<T> abstractComposite) {
        list.add(abstractComposite);
    }

    /**
     * 从当前节点开始，执行整棵树中的所有节点。
     *
     * 这里使用的是“广度优先遍历”，也叫“层序遍历”。
     *
     * 执行特点：
     *
     * 1. 先执行当前层所有节点；
     * 2. 再执行下一层所有节点；
     * 3. 某个节点校验失败并抛出异常时，整个流程立即中断；
     * 4. 所有节点都执行完成且没有异常，表示整套校验通过。
     *
     * 假设树结构为：
     *
     *              根节点
     *             /      \
     *       节目校验     用户校验
     *          |
     *       票档校验
     *
     * 执行顺序大致为：
     *
     * 根节点
     * → 节目校验
     * → 用户校验
     * → 票档校验
     *
     * @param param 本次业务请求参数
     */
    public void allExecute(T param) {

        /*
         * 创建队列，用来保存“接下来需要执行”的节点。
         *
         * 队列特点是先进先出，适合进行树的广度优先遍历。
         */
        Queue<AbstractComposite<T>> queue = new LinkedList<>();

        /*
         * this 表示当前对象。
         *
         * 外部通常是在根节点上调用 allExecute()，
         * 所以先把根节点加入队列。
         */
        queue.add(this);

        /*
         * 只要队列中还有待执行节点，就继续执行。
         */
        while (!queue.isEmpty()) {

            /*
             * 记录当前层一共有多少个节点。
             *
             * 这样本轮 for 循环只处理当前层节点，
             * 当前层节点的子节点留到下一轮 while 再执行。
             */
            int levelSize = queue.size();

            /*
             * 执行当前层中的所有节点。
             */
            for (int i = 0; i < levelSize; i++) {

                /*
                 * 从队列头部取出一个待执行节点。
                 */
                AbstractComposite<T> current = queue.poll();

                /*
                 * 理论上队列非空时 poll() 不会返回 null。
                 *
                 * assert 只有在 JVM 开启断言时才会生效，
                 * 因此它不适合用来处理正式业务异常。
                 */
                assert current != null;

                /*
                 * 执行当前节点自己的业务校验。
                 *
                 * 例如当前节点是节目校验节点，
                 * 就会调用节目校验节点子类实现的 execute(param)。
                 *
                 * 如果这里抛出异常，后续节点不会继续执行。
                 */
                current.execute(param);

                /*
                 * 当前节点执行完成后，
                 * 把它的所有直接子节点加入队列尾部。
                 *
                 * 这些子节点不会立刻执行，
                 * 而是在当前层全部执行结束后，
                 * 进入下一轮 while 时再执行。
                 */
                queue.addAll(current.list);
            }
        }
    }
}