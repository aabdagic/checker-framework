package dataflow.analysis;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Map.Entry;

import dataflow.cfg.block.Block;
import dataflow.cfg.block.ExceptionBlock;
import dataflow.cfg.block.RegularBlock;
import dataflow.cfg.node.Node;

import com.sun.source.tree.Tree;

/**
 * An {@link AnalysisResult} represents the result of a dataflow analysis by
 * providing the abstract values given a node or a tree. Note that it does not
 * keep track of custom results computed by some analysis.
 *
 * @author Stefan Heule
 *
 * @param <A>
 *            type of the abstract value that is tracked.
 */
public class AnalysisResult<A extends AbstractValue<A>, S extends Store<S>> {

    /** Abstract values of nodes. */
    protected final IdentityHashMap<Node, A> nodeValues;

    /**
     * Maps from AST {@link Tree}s to {@link Node}s.  Every Tree that produces
     * a value will have at least one corresponding Node.  Trees
     * that undergo conversions, such as boxing or unboxing, can map to two
     * distinct Nodes.  The Node for the pre-conversion value is stored
     * in treeLookup, while the Node for the post-conversion value
     * is stored in convertedTreeLookup.
     */
    protected final IdentityHashMap<Tree, Node> treeLookup;

    /** Map from AST {@link Tree}s to post-conversion {@link Node}s. */
    protected final IdentityHashMap<Tree, Node> convertedTreeLookup;

    /**
     * The stores before every method call.
     */
    protected final IdentityHashMap<Block, TransferInput<A, S>> stores;

    /**
     * Initialize with a given node-value mapping.
     */
    public AnalysisResult(Map<Node, A> nodeValues,
            IdentityHashMap<Block, TransferInput<A, S>> stores,
            IdentityHashMap<Tree, Node> treeLookup,
            IdentityHashMap<Tree, Node> convertedTreeLookup) {
        this.nodeValues = new IdentityHashMap<>(nodeValues);
        this.treeLookup = new IdentityHashMap<>(treeLookup);
        this.convertedTreeLookup = new IdentityHashMap<>(convertedTreeLookup);
        this.stores = stores;
    }

    /**
     * Initialize empty result.
     */
    public AnalysisResult() {
        nodeValues = new IdentityHashMap<>();
        treeLookup = new IdentityHashMap<>();
        convertedTreeLookup = new IdentityHashMap<>();
        stores = new IdentityHashMap<>();
    }

    /**
     * Combine with another analysis result.
     */
    public void combine(AnalysisResult<A, S> other) {
        for (Entry<Node, A> e : other.nodeValues.entrySet()) {
            nodeValues.put(e.getKey(), e.getValue());
        }
        for (Entry<Tree, Node> e : other.treeLookup.entrySet()) {
            treeLookup.put(e.getKey(), e.getValue());
        }
        for (Entry<Tree, Node> e : other.convertedTreeLookup.entrySet()) {
            convertedTreeLookup.put(e.getKey(), e.getValue());
        }
        for (Entry<Block, TransferInput<A, S>> e : other.stores.entrySet()) {
            stores.put(e.getKey(), e.getValue());
        }
    }

    /**
     * @return The abstract value for {@link Node} {@code n}, or {@code null} if
     *         no information is available.
     */
    public/* @Nullable */A getValue(Node n) {
        return nodeValues.get(n);
    }

    /**
     * @return The abstract value for {@link Tree} {@code t}, or {@code null} if
     *         no information is available.
     */
    public/* @Nullable */A getValue(Tree t) {
        A val = getValue(treeLookup.get(t));
        return val;
    }

    /**
     * @return The post-conversion abstract value for {@link Tree}
     *         {@code t}, or {@code null} if no information is
     *         available.
     */
    public/* @Nullable */A getConvertedValue(Tree t) {
        A val = getValue(convertedTreeLookup.get(t));
        return val;
    }

    /**
     * @return The {@link Node} for a given {@link Tree}.
     */
    public/* @Nullable */Node getNodeForTree(Tree tree) {
        return treeLookup.get(tree);
    }

    /**
     * @return The post-conversion {@link Node} for a given {@link Tree}.
     */
    public/* @Nullable */Node getConvertedNodeForTree(Tree tree) {
        return convertedTreeLookup.get(tree);
    }

    /**
     * @return The store immediately before a given {@link Tree}.
     */
    public S getStoreBefore(Tree tree) {
        Node node = getNodeForTree(tree);
        if (node == null) {
            return null;
        }
        return runAnalysisFor(node, true);
    }

    /**
     * @return The store immediately after a given {@link Tree}.
     */
    public S getStoreAfter(Tree tree) {
        Node node = getNodeForTree(tree);
        if (node == null) {
            return null;
        }
        return runAnalysisFor(node, false);
    }

    /**
     * Runs the analysis again within the block of {@code node} and returns the
     * store at the location of {@code node}. If {@code before} is true, then
     * the store immediately before the {@link Node} {@code node} is returned.
     * Otherwise, the store after {@code node} is returned.
     *
     * <p>
     * If the given {@link Node} cannot be reached (in the control flow graph),
     * then {@code null} is returned.
     */
    protected S runAnalysisFor(Node node, boolean before) {
        Block block = node.getBlock();
        TransferInput<A, S> transferInput = stores.get(block);
        if (transferInput == null) {
            return null;
        }
        return runAnalysisFor(node, before, transferInput);
    }

    /**
     * Runs the analysis again within the block of {@code node} and returns the
     * store at the location of {@code node}. If {@code before} is true, then
     * the store immediately before the {@link Node} {@code node} is returned.
     * Otherwise, the store after {@code node} is returned.
     */
    public static <A extends AbstractValue<A>, S extends Store<S>> S runAnalysisFor(
            Node node, boolean before, TransferInput<A, S> transferInput) {
        assert node != null;
        Block block = node.getBlock();
        assert transferInput != null;
        Analysis<A, S, ?> analysis = transferInput.analysis;
        Node oldCurrentNode = analysis.currentNode;

        if (analysis.isRunning) {
            return analysis.currentStore.getRegularStore();
        }
        analysis.isRunning = true;
        try {
            switch (block.getType()) {
            case REGULAR_BLOCK: {
                RegularBlock rb = (RegularBlock) block;

                // Apply transfer function to contents until we found the node
                // we
                // are looking for.
                TransferInput<A, S> store = transferInput;
                TransferResult<A, S> transferResult = null;
                for (Node n : rb.getContents()) {
                    analysis.currentNode = n;
                    if (n == node && before) {
                        return store.getRegularStore();
                    }
                    transferResult = analysis.callTransferFunction(n, store);
                    if (n == node) {
                        return transferResult.getRegularStore();
                    }
                    store = new TransferInput<>(n, analysis, transferResult);
                }
                // This point should never be reached. If the block of 'node' is
                // 'block', then 'node' must be part of the contents of 'block'.
                assert false;
                return null;
            }

            case EXCEPTION_BLOCK: {
                ExceptionBlock eb = (ExceptionBlock) block;

                // apply transfer function to content
                assert eb.getNode() == node;
                if (before) {
                    return transferInput.getRegularStore();
                }
                analysis.currentNode = node;
                TransferResult<A, S> transferResult = analysis
                        .callTransferFunction(node, transferInput);
                return transferResult.getRegularStore();
            }

            default:
                // Only regular blocks and exceptional blocks can hold nodes.
                assert false;
                break;
            }

            return null;
        } finally {
            analysis.currentNode = oldCurrentNode;
            analysis.isRunning = false;
        }
    }
}
