package com.stellaris.initialize.impl.composite;

import com.stellaris.enums.BaseCode;
import com.stellaris.exception.StellarisFrameException;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 组合模式组件容器。
 *
 * <p>容器在 Spring 应用启动阶段收集所有 {@link AbstractComposite} Bean，
 * 按业务类型分别构建组件树，并为后续业务校验提供统一执行入口。</p>
 *
 * <p>一棵组件树由以下三个维度确定：</p>
 * <ol>
 *     <li>{@link AbstractComposite#type()}：组件所属业务类型，同一类型的组件组成一棵树；</li>
 *     <li>{@link AbstractComposite#executeTier()}：组件所在层级；</li>
 *     <li>{@link AbstractComposite#executeOrder()}：组件在当前层的唯一编号，供下一层子节点定位父节点。</li>
 * </ol>
 *
 * <p>子节点通过 {@link AbstractComposite#executeParentOrder()} 声明父节点编号。
 * 容器只会在子节点的上一层查找该编号，因此树的层级需要连续，不能跳层。</p>
 *
 * <p>树构建完成后，调用 {@link #execute(String, Object)} 会从对应根节点开始，
 * 由 {@link AbstractComposite#allExecute(Object)} 按广度优先顺序执行整棵树。</p>
 *
 * @param <T> 执行组件树时传入的业务参数类型
 * @author: xz_y
 */
public class CompositeContainer<T> {

    /**
     * 保存所有已经构建完成的组件树。
     *
     * <p>key：组件声明的业务类型 {@link AbstractComposite#type()}；
     * value：该业务类型对应组件树的根节点。</p>
     *
     * <p>例如：</p>
     * <pre>
     * PROGRAM_ORDER_CREATE_CHECK -> 创建订单校验树根节点
     * PROGRAM_ORDER_CANCEL_CHECK -> 取消订单校验树根节点
     * </pre>
     */
    private final Map<String, AbstractComposite> allCompositeInterfaceMap = new HashMap<>();

    /**
     * 初始化容器并构建全部组件树。
     *
     * <p>执行流程：</p>
     * <ol>
     *     <li>从 Spring 容器取得所有 {@link AbstractComposite} 类型的 Bean；</li>
     *     <li>按照组件的业务 {@code type} 分组；</li>
     *     <li>为每个业务类型分别构建一棵组件树；</li>
     *     <li>将有效根节点缓存到 {@link #allCompositeInterfaceMap}，供运行期执行。</li>
     * </ol>
     *
     * <p>该方法由应用启动初始化器调用。组件 Bean 此时已经完成 Spring 注入，
     * 因而构建出的树可以直接参与后续业务处理。</p>
     *
     * @param applicationEvent 当前 Spring 可配置应用上下文
     */
    public void init(ConfigurableApplicationContext applicationEvent){
        // key 为 Spring Bean 名称，value 为具体组合节点实例。
        Map<String, AbstractComposite> compositeInterfaceMap = applicationEvent.getBeansOfType(AbstractComposite.class);

        // 同一业务类型的节点归为一组，每一组将在后面独立构建一棵树。
        Map<String, List<AbstractComposite>> collect = compositeInterfaceMap.values().stream().collect(Collectors.groupingBy(AbstractComposite::type));
        collect.forEach((k,v) -> {
            // 根据节点声明的层级、当前层编号和父节点编号建立父子关系。
            AbstractComposite root = build(v);
            if (Objects.nonNull(root)) {
                // 只有成功找到根节点的业务类型才会注册到运行期容器。
                allCompositeInterfaceMap.put(k, root);
            }
        });
    }

    /**
     * 执行指定业务类型对应的整棵组件树。
     *
     * <p>容器先根据 {@code type} 找到根节点，再从根节点开始执行所有节点。
     * 任意节点抛出异常时，后续节点不再继续执行，异常直接向上层传播。</p>
     *
     * @param type 组件树的业务类型，必须与节点的 {@link AbstractComposite#type()} 返回值一致
     * @param param 传递给树中每个组件节点的业务参数
     * @throws StellarisFrameException 指定业务类型没有对应组件树时抛出
     */
    public void execute(String type,T param){
        // 未注册通常表示 type 错误，或该类型的节点配置无法构建出有效根节点。
        AbstractComposite compositeInterface = Optional.ofNullable(allCompositeInterfaceMap.get(type))
                .orElseThrow(() -> new StellarisFrameException(BaseCode.COMPOSITE_NOT_EXIST));

        // allExecute 从根节点开始按层执行，所有节点共享同一个业务参数对象。
        compositeInterface.allExecute(param);
    }

    /**
     * 递归建立相邻两层组件之间的父子关系。
     *
     * <p>{@code groupedByTier} 的两层 key 含义如下：</p>
     * <pre>
     * 第一层 key：executeTier，节点所在层级
     * 第二层 key：executeOrder，节点在当前层的编号
     * value：具体组件节点
     * </pre>
     *
     * <p>处理当前层 {@code currentTier} 时，方法遍历下一层的所有节点，读取每个子节点的
     * {@link AbstractComposite#executeParentOrder()}，然后在当前层按 {@code executeOrder}
     * 查找父节点并完成挂载。处理完毕后继续递归处理下一层。</p>
     *
     * <p>注意：本实现通过 {@code currentTier + 1} 查找下一层，所以组件层级必须使用连续整数。
     * 如果中间层缺失，递归会结束，更深层节点不会被挂载。</p>
     *
     * @param groupedByTier 按“层级 -> 当前层编号 -> 组件”组织的组件映射
     * @param currentTier   当前正在处理的父节点层级
     */
    private static void buildTree(Map<Integer, Map<Integer, AbstractComposite>> groupedByTier, int currentTier) {
        // 当前层节点是本轮可能被匹配到的父节点。
        Map<Integer, AbstractComposite> currentLevelComponents = groupedByTier.get(currentTier);

        // 只有紧邻的下一层节点会在本轮尝试挂载。
        Map<Integer, AbstractComposite> nextLevelComponents = groupedByTier.get(currentTier + 1);

        // 当前层不存在说明层级已经结束，或配置中出现了跳层，终止递归。
        if (currentLevelComponents == null) {
            return;
        }

        if (nextLevelComponents != null) {
            for (AbstractComposite child : nextLevelComponents.values()) {
                // 子节点声明的是父节点在上一层的 executeOrder，而不是 Spring Bean 名称。
                Integer parentOrder = child.executeParentOrder();

                // null 或 0 表示节点未声明父节点，通常用于标识根节点，因此不执行挂载。
                if (parentOrder == null || parentOrder == 0) {
                    continue;
                }

                // 在当前层按编号定位父节点。
                AbstractComposite parent = currentLevelComponents.get(parentOrder);
                if (parent != null) {
                    // 将子节点加入父节点的直接子节点列表，完成一条树边的构建。
                    parent.add(child);
                }
            }
        }

        // 当前层与下一层处理完成后，让下一层成为新的父节点层，继续向下构建。
        buildTree(groupedByTier, currentTier + 1);
    }

    /**
     * 根据同一业务类型的组件集合构建组件树，并返回根节点。
     *
     * <p>构建过程：</p>
     * <ol>
     *     <li>按 {@code executeTier} 分层；</li>
     *     <li>每层再按 {@code executeOrder} 建立节点索引；</li>
     *     <li>从最小层级开始，递归建立相邻层之间的父子关系；</li>
     *     <li>从最小层中选取父节点编号为 {@code null} 或 {@code 0} 的节点作为根节点。</li>
     * </ol>
     *
     * <p>同一层内的 {@code executeOrder} 必须唯一；如果重复，后遍历到的组件会覆盖先前组件。
     * 同一业务类型应只配置一个有效根节点，否则当前实现只返回查找到的第一个根节点。</p>
     *
     * @param components 同一业务类型下的全部组件
     * @return 构建完成的根节点；集合为空或没有有效根节点时返回 {@code null}
     */
    private static AbstractComposite build(Collection<AbstractComposite> components) {
        // TreeMap 使层级 key 保持升序，便于确定整棵树的最小层级。
        Map<Integer, Map<Integer, AbstractComposite>> groupedByTier = new TreeMap<>();

        for (AbstractComposite component : components) {
            // 第一层按 executeTier 分组，第二层以 executeOrder 作为节点索引。
            groupedByTier.computeIfAbsent(component.executeTier(), k -> new HashMap<>(16))
                    .put(component.executeOrder(), component);
        }

        // 最小层级就是根节点应当所在的层级；空集合没有最小层级。
        Integer minTier = groupedByTier.keySet().stream().min(Integer::compare).orElse(null);
        if (minTier == null) {
            return null;
        }

        // 从根层开始，逐层把子节点挂载到上一层对应父节点。
        buildTree(groupedByTier, minTier);

        // 根节点没有父节点，其 executeParentOrder 按约定应为 null 或 0。
        return groupedByTier.get(minTier).values().stream()
                .filter(c -> c.executeParentOrder() == null || c.executeParentOrder() == 0)
                .findFirst()
                .orElse(null);
    }
}
