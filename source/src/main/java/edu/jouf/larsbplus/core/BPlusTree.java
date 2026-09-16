package edu.jouf.larsbplus.core;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Conventional single-threaded, in-memory, array-backed B+ tree for unique long -> long mappings.
 *
 * <p>The class intentionally exposes a small set of research hooks used by LARS-B+:
 * leaf-level region inspection, bounded local SLACKEN/COMPACT actions, locality-footprint
 * estimation, regional snapshots, and a safety predicate that prevents trial mutations from
 * forcing structural changes outside the monitored region.</p>
 *
 * <p>This is research code, not a concurrent production index.</p>
 */
public final class BPlusTree {

    public enum LocalAction { SLACKEN, COMPACT }

    public record Entry(long key, long value) {}

    public record RangeResult(List<Entry> entries,
                              int leavesVisited,
                              Map<Long, Integer> leavesVisitedByRegion,
                              Map<Long, Integer> resultsByRegion) {}

    /** Caller-owned primitive trace for monitored range searches. */
    public static final class RangeTrace {
        private long[] regionIds = new long[4];
        private int[] leavesVisited = new int[4];
        private int[] results = new int[4];
        private int regionCount;
        private int totalLeavesVisited;

        public int regionCount() { return regionCount; }
        public int totalLeavesVisited() { return totalLeavesVisited; }
        public long regionId(int index) { return regionIds[index]; }
        public int leavesVisited(int index) { return leavesVisited[index]; }
        public int results(int index) { return results[index]; }

        private void reset() {
            regionCount = 0;
            totalLeavesVisited = 0;
        }

        private int visit(long regionId) {
            totalLeavesVisited++;
            for (int i = 0; i < regionCount; i++) {
                if (regionIds[i] == regionId) {
                    leavesVisited[i]++;
                    return i;
                }
            }
            if (regionCount == regionIds.length) {
                int capacity = regionIds.length << 1;
                regionIds = Arrays.copyOf(regionIds, capacity);
                leavesVisited = Arrays.copyOf(leavesVisited, capacity);
                results = Arrays.copyOf(results, capacity);
            }
            int index = regionCount++;
            regionIds[index] = regionId;
            leavesVisited[index] = 1;
            results[index] = 0;
            return index;
        }

        private void recordResults(int regionIndex, int count) {
            results[regionIndex] += count;
        }
    }

    /** Caller-owned scratch result for single-threaded monitoring; never retained by the tree. */
    public static final class PointSearchResult {
        private long regionId;
        private long value;
        private boolean found;
        public long regionId() { return regionId; }
        public long value() { return value; }
        public boolean found() { return found; }
    }

    public record SearchTrace(OptionalLong value, long regionId) {}

    public record MutationTrace(boolean changed, long regionIdBefore) {}

    public record LeafView(long leafId,
                           int indexInRegion,
                           int size,
                           int capacity,
                           long firstKey,
                           long lastKey) {
        public double occupancy() {
            return capacity == 0 ? 0.0 : ((double) size) / capacity;
        }
    }

    public record RegionView(long regionId,
                             int leafCount,
                             int recordCount,
                             int leafCapacity,
                             double averageOccupancy,
                             double minimumOccupancy,
                             long firstKey,
                             long lastKey,
                             boolean canAddLeafWithoutParentSplit,
                             boolean canRemoveLeafWithoutParentUnderflow,
                             List<LeafView> leaves) {}

    /** Caller-owned allocation-free structural summary for monitoring-window closure. */
    public static final class RegionMetrics {
        private int leafCount;
        private int recordCount;
        private double averageOccupancy;
        private double minimumOccupancy;
        public int leafCount() { return leafCount; }
        public int recordCount() { return recordCount; }
        public double averageOccupancy() { return averageOccupancy; }
        public double minimumOccupancy() { return minimumOccupancy; }
    }

    public record StructuralCounters(long leafSplits, long leafMerges) {
        public StructuralCounters add(StructuralCounters other) {
            return new StructuralCounters(leafSplits + other.leafSplits,
                    leafMerges + other.leafMerges);
        }
    }

    public record ActionEstimate(LocalAction action,
                                 long regionId,
                                 int fromLeafInclusive,
                                 int toLeafInclusive,
                                 int recordsInWindow,
                                 int recordsMoved,
                                 int leavesTouched,
                                 int leafDelta,
                                 boolean feasible,
                                 String reason) {}

    public record ActionResult(ActionEstimate estimate, long elapsedNanos) {}

    public sealed interface Mutation permits InsertMutation, DeleteMutation, UpdateMutation {
        long key();
        boolean replayOn(BPlusTree tree);
    }

    public record InsertMutation(long key, long value) implements Mutation {
        @Override public boolean replayOn(BPlusTree tree) { return tree.insert(key, value); }
    }

    public record DeleteMutation(long key) implements Mutation {
        @Override public boolean replayOn(BPlusTree tree) { return tree.delete(key); }
    }

    public record UpdateMutation(long key, long value) implements Mutation {
        @Override public boolean replayOn(BPlusTree tree) { return tree.update(key, value); }
    }

    /**
     * Snapshot of a leaf-level region. It deliberately stores references to the original leaves
     * plus copies of their arrays. This lets rollback reattach leaves removed by a candidate or
     * by trial-local merges, while discarding newly-created leaves that did not exist beforehand.
     */
    public static final class RegionSnapshot {
        private final long regionId;
        private final InternalNode region;
        private final List<LeafNode> originalLeaves;
        private final List<long[]> keys;
        private final List<long[]> values;
        private final int[] sizes;
        private final LeafNode outsidePrev;
        private final LeafNode outsideNext;
        private final int originalRecordCount;

        private RegionSnapshot(long regionId,
                               InternalNode region,
                               List<LeafNode> originalLeaves,
                               List<long[]> keys,
                               List<long[]> values,
                               int[] sizes,
                               LeafNode outsidePrev,
                               LeafNode outsideNext,
                               int originalRecordCount) {
            this.regionId = regionId;
            this.region = region;
            this.originalLeaves = originalLeaves;
            this.keys = keys;
            this.values = values;
            this.sizes = sizes;
            this.outsidePrev = outsidePrev;
            this.outsideNext = outsideNext;
            this.originalRecordCount = originalRecordCount;
        }

        public long regionId() { return regionId; }
    }

    private abstract static class Node {
        final long id;
        InternalNode parent;
        Node(long id) { this.id = id; }
        abstract boolean isLeaf();
        abstract long firstKey();
    }

    private static final class LeafNode extends Node {
        final long[] keys;
        final long[] values;
        int size;
        LeafNode prev;
        LeafNode next;

        LeafNode(long id, int maxLeafKeys) {
            super(id);
            this.keys = new long[maxLeafKeys + 1]; // +1 for transient overflow
            this.values = new long[maxLeafKeys + 1];
        }

        @Override boolean isLeaf() { return true; }
        @Override long firstKey() { return size == 0 ? Long.MIN_VALUE : keys[0]; }
    }

    private static final class InternalNode extends Node {
        final long[] keys;
        final Node[] children;
        int childCount;

        InternalNode(long id, int maxChildren) {
            super(id);
            this.keys = new long[maxChildren];       // maxChildren-1 separators + transient overflow
            this.children = new Node[maxChildren + 1];
        }

        @Override boolean isLeaf() { return false; }
        @Override long firstKey() { return childCount == 0 ? Long.MIN_VALUE : children[0].firstKey(); }
    }

    private final int maxLeafKeys;
    private final int maxInternalChildren;
    private final int minLeafKeys;
    private final int minInternalChildren;
    private final AtomicLong nextNodeId = new AtomicLong(1);
    private final Map<Long, StructuralCounters> structuralCounters = new HashMap<>();
    private final Map<Long, InternalNode> leafLevelRegionsById = new HashMap<>();

    private Node root;
    private long size;
    private boolean suppressStructuralCounters;

    public BPlusTree() {
        this(64, 64);
    }

    public BPlusTree(int maxLeafKeys, int maxInternalChildren) {
        if (maxLeafKeys < 4) throw new IllegalArgumentException("maxLeafKeys must be >= 4");
        if (maxInternalChildren < 4) throw new IllegalArgumentException("maxInternalChildren must be >= 4");
        this.maxLeafKeys = maxLeafKeys;
        this.maxInternalChildren = maxInternalChildren;
        this.minLeafKeys = (maxLeafKeys + 1) / 2;
        this.minInternalChildren = (maxInternalChildren + 1) / 2;
        this.root = newLeaf();
        rebuildLeafLevelRegionIndex();
    }


    /**
     * Builds a compact B+ tree bottom-up from strictly sorted unique entries.
     * This is used only by global-rebuild baselines so a rebuild is a real
     * physical reconstruction rather than a sequence of ordinary inserts.
     */
    public static BPlusTree bulkLoadSorted(List<Entry> sortedEntries,
                                           int maxLeafKeys,
                                           int maxInternalChildren,
                                           double targetLeafFill) {
        Objects.requireNonNull(sortedEntries, "sortedEntries");
        if (!(targetLeafFill >= 0.50 && targetLeafFill <= 1.0)) {
            throw new IllegalArgumentException("targetLeafFill must be in [0.50,1.0]");
        }
        for (int i = 1; i < sortedEntries.size(); i++) {
            if (sortedEntries.get(i - 1).key() >= sortedEntries.get(i).key()) {
                throw new IllegalArgumentException("entries must be strictly sorted by unique key");
            }
        }

        BPlusTree tree = new BPlusTree(maxLeafKeys, maxInternalChildren);
        tree.structuralCounters.clear();
        tree.size = sortedEntries.size();

        if (sortedEntries.isEmpty()) {
            tree.root = tree.newLeaf();
            tree.rebuildLeafLevelRegionIndex();
            return tree;
        }
        if (sortedEntries.size() <= maxLeafKeys) {
            LeafNode leaf = tree.newLeaf();
            leaf.size = sortedEntries.size();
            for (int i = 0; i < sortedEntries.size(); i++) {
                Entry e = sortedEntries.get(i);
                leaf.keys[i] = e.key();
                leaf.values[i] = e.value();
            }
            tree.root = leaf;
            tree.rebuildLeafLevelRegionIndex();
            return tree;
        }

        int target = Math.max(tree.minLeafKeys,
                Math.min(maxLeafKeys, (int) Math.floor(maxLeafKeys * targetLeafFill)));
        int leafCount = Math.max(2, ceilDiv(sortedEntries.size(), target));
        while (leafCount > 1 && sortedEntries.size() / leafCount < tree.minLeafKeys) leafCount--;
        while (ceilDiv(sortedEntries.size(), leafCount) > maxLeafKeys) leafCount++;

        List<Node> level = new ArrayList<>(leafCount);
        LeafNode previous = null;
        int base = sortedEntries.size() / leafCount;
        int extra = sortedEntries.size() % leafCount;
        int cursor = 0;
        for (int i = 0; i < leafCount; i++) {
            int count = base + (i < extra ? 1 : 0);
            LeafNode leaf = tree.newLeaf();
            leaf.size = count;
            for (int j = 0; j < count; j++) {
                Entry e = sortedEntries.get(cursor++);
                leaf.keys[j] = e.key();
                leaf.values[j] = e.value();
            }
            leaf.prev = previous;
            if (previous != null) previous.next = leaf;
            previous = leaf;
            level.add(leaf);
        }

        while (level.size() > 1) {
            if (level.size() <= maxInternalChildren) {
                InternalNode rootNode = tree.newInternal();
                rootNode.childCount = level.size();
                for (int i = 0; i < level.size(); i++) {
                    rootNode.children[i] = level.get(i);
                    level.get(i).parent = rootNode;
                }
                tree.recomputeSeparators(rootNode);
                level = List.of(rootNode);
                break;
            }

            int parentCount = ceilDiv(level.size(), maxInternalChildren);
            while (level.size() / parentCount < tree.minInternalChildren) parentCount--;
            int childBase = level.size() / parentCount;
            int childExtra = level.size() % parentCount;
            int childCursor = 0;
            List<Node> next = new ArrayList<>(parentCount);
            for (int pIndex = 0; pIndex < parentCount; pIndex++) {
                int childCount = childBase + (pIndex < childExtra ? 1 : 0);
                InternalNode parent = tree.newInternal();
                parent.childCount = childCount;
                for (int j = 0; j < childCount; j++) {
                    Node child = level.get(childCursor++);
                    parent.children[j] = child;
                    child.parent = parent;
                }
                tree.recomputeSeparators(parent);
                next.add(parent);
            }
            level = next;
        }

        tree.root = level.get(0);
        tree.root.parent = null;
        tree.rebuildLeafLevelRegionIndex();
        tree.validate();
        return tree;
    }

    public long size() { return size; }
    public boolean isEmpty() { return size == 0; }
    public int maxLeafKeys() { return maxLeafKeys; }
    public int maxInternalChildren() { return maxInternalChildren; }
    public int minLeafKeys() { return minLeafKeys; }
    public int minInternalChildren() { return minInternalChildren; }

    public OptionalLong search(long key) {
        return searchWithRegion(key).value();
    }

    public SearchTrace searchWithRegion(long key) {
        LeafNode leaf = findLeaf(key);
        long regionId = leaf.parent == null ? -1L : leaf.parent.id;
        int pos = binarySearch(leaf.keys, leaf.size, key);
        OptionalLong value = pos >= 0 ? OptionalLong.of(leaf.values[pos]) : OptionalLong.empty();
        return new SearchTrace(value, regionId);
    }

    /** Search without allocating a trace or OptionalLong on the monitored foreground path. */
    public void searchInto(long key, PointSearchResult result) {
        LeafNode leaf = findLeaf(key);
        result.regionId = leaf.parent == null ? -1L : leaf.parent.id;
        int pos = binarySearch(leaf.keys, leaf.size, key);
        result.found = pos >= 0;
        result.value = pos >= 0 ? leaf.values[pos] : 0;
    }

    public boolean containsKey(long key) {
        return search(key).isPresent();
    }

    public boolean insert(long key, long value) {
        return insertWithRegion(key, value).changed();
    }

    public MutationTrace insertWithRegion(long key, long value) {
        LeafNode leaf = findLeaf(key);
        long regionId = leaf.parent == null ? -1L : leaf.parent.id;
        int pos = binarySearch(leaf.keys, leaf.size, key);
        if (pos >= 0) return new MutationTrace(false, regionId);
        int insertion = -pos - 1;
        insertIntoLeafAt(leaf, insertion, key, value);
        size++;
        if (leaf.size > maxLeafKeys) {
            splitLeaf(leaf);
        } else {
            refreshAncestors(leaf.parent);
        }
        return new MutationTrace(true, regionId);
    }

    public boolean update(long key, long value) {
        return updateWithRegion(key, value).changed();
    }

    public MutationTrace updateWithRegion(long key, long value) {
        LeafNode leaf = findLeaf(key);
        long regionId = leaf.parent == null ? -1L : leaf.parent.id;
        int pos = binarySearch(leaf.keys, leaf.size, key);
        if (pos < 0) return new MutationTrace(false, regionId);
        leaf.values[pos] = value;
        return new MutationTrace(true, regionId);
    }

    public boolean delete(long key) {
        return deleteWithRegion(key).changed();
    }

    public MutationTrace deleteWithRegion(long key) {
        LeafNode leaf = findLeaf(key);
        long regionId = leaf.parent == null ? -1L : leaf.parent.id;
        int pos = binarySearch(leaf.keys, leaf.size, key);
        if (pos < 0) return new MutationTrace(false, regionId);
        removeFromLeafAt(leaf, pos);
        size--;

        if (leaf == root) return new MutationTrace(true, regionId);
        if (leaf.size >= minLeafKeys) {
            refreshAncestors(leaf.parent);
            return new MutationTrace(true, regionId);
        }
        rebalanceLeaf(leaf);
        return new MutationTrace(true, regionId);
    }

    public List<Entry> rangeSearch(long fromInclusive, long toInclusive) {
        if (fromInclusive > toInclusive || size == 0) return List.of();
        List<Entry> out = new ArrayList<>();
        LeafNode leaf = findLeaf(fromInclusive);
        while (leaf != null) {
            int start = lowerBound(leaf.keys, leaf.size, fromInclusive);
            for (int i = start; i < leaf.size; i++) {
                long k = leaf.keys[i];
                if (k > toInclusive) return List.copyOf(out);
                out.add(new Entry(k, leaf.values[i]));
            }
            if (leaf.size > 0 && leaf.keys[leaf.size - 1] > toInclusive) break;
            leaf = leaf.next;
        }
        return List.copyOf(out);
    }

    public RangeResult rangeSearchWithTrace(long fromInclusive, long toInclusive) {
        if (fromInclusive > toInclusive || size == 0) {
            return new RangeResult(List.of(), 0, Map.of(), Map.of());
        }

        List<Entry> out = new ArrayList<>();
        Map<Long, Integer> visitedByRegion = new HashMap<>();
        Map<Long, Integer> resultsByRegion = new HashMap<>();
        int leavesVisited = 0;
        LeafNode leaf = findLeaf(fromInclusive);

        while (leaf != null) {
            leavesVisited++;
            long regionId = leaf.parent == null ? -1L : leaf.parent.id;
            visitedByRegion.merge(regionId, 1, Integer::sum);

            int start = lowerBound(leaf.keys, leaf.size, fromInclusive);
            for (int i = start; i < leaf.size; i++) {
                long k = leaf.keys[i];
                if (k > toInclusive) {
                    return new RangeResult(List.copyOf(out), leavesVisited,
                            Map.copyOf(visitedByRegion), Map.copyOf(resultsByRegion));
                }
                out.add(new Entry(k, leaf.values[i]));
                resultsByRegion.merge(regionId, 1, Integer::sum);
            }
            if (leaf.size > 0 && leaf.keys[leaf.size - 1] > toInclusive) break;
            leaf = leaf.next;
        }

        return new RangeResult(List.copyOf(out), leavesVisited,
                Map.copyOf(visitedByRegion), Map.copyOf(resultsByRegion));
    }

    /**
     * Range search with the same regional evidence written into reusable primitive storage.
     * The returned entry list has the same immutable snapshot semantics as {@link #rangeSearch}.
     */
    public List<Entry> rangeSearchWithTrace(long fromInclusive, long toInclusive, RangeTrace trace) {
        Objects.requireNonNull(trace, "trace");
        trace.reset();
        if (fromInclusive > toInclusive || size == 0) return List.of();

        List<Entry> out = new ArrayList<>();
        LeafNode leaf = findLeaf(fromInclusive);
        while (leaf != null) {
            long regionId = leaf.parent == null ? -1L : leaf.parent.id;
            int regionIndex = trace.visit(regionId);
            int start = lowerBound(leaf.keys, leaf.size, fromInclusive);
            int added = 0;
            for (int i = start; i < leaf.size; i++) {
                long k = leaf.keys[i];
                if (k > toInclusive) {
                    trace.recordResults(regionIndex, added);
                    return List.copyOf(out);
                }
                out.add(new Entry(k, leaf.values[i]));
                added++;
            }
            trace.recordResults(regionIndex, added);
            if (leaf.size > 0 && leaf.keys[leaf.size - 1] > toInclusive) break;
            leaf = leaf.next;
        }
        return List.copyOf(out);
    }

    public long regionIdForKey(long key) {
        LeafNode leaf = findLeaf(key);
        return leaf.parent == null ? -1L : leaf.parent.id;
    }

    public long leafIdForKey(long key) {
        return findLeaf(key).id;
    }

    public List<RegionView> regions() {
        List<RegionView> out = new ArrayList<>();
        collectRegions(root, out);
        out.sort(Comparator.comparingLong(RegionView::firstKey));
        return List.copyOf(out);
    }

    public Optional<RegionView> region(long regionId) {
        InternalNode node = leafLevelRegionsById.get(regionId);
        if (node == null) return Optional.empty();
        return Optional.of(toRegionView(node));
    }

    /** O(1) membership test against the maintained leaf-level region registry. */
    public boolean hasLeafLevelRegion(long regionId) {
        return leafLevelRegionsById.containsKey(regionId);
    }

    /**
     * Fills a caller-owned structural summary without allocating LeafView objects or lists.
     * Returns false if the region id is no longer an active leaf-level region.
     */
    public boolean fillRegionMetrics(long regionId, RegionMetrics out) {
        Objects.requireNonNull(out, "out");
        InternalNode region = leafLevelRegionsById.get(regionId);
        if (region == null) return false;
        int records = 0;
        double minOcc = Double.POSITIVE_INFINITY;
        for (int i = 0; i < region.childCount; i++) {
            LeafNode leaf = (LeafNode) region.children[i];
            records += leaf.size;
            minOcc = Math.min(minOcc, ((double) leaf.size) / maxLeafKeys);
        }
        out.leafCount = region.childCount;
        out.recordCount = records;
        out.averageOccupancy = region.childCount == 0 ? 0.0
                : ((double) records) / (region.childCount * maxLeafKeys);
        out.minimumOccupancy = minOcc == Double.POSITIVE_INFINITY ? 0.0 : minOcc;
        return true;
    }

    public long leafCount() {
        long count = 0;
        LeafNode leaf = leftmostLeaf();
        while (leaf != null) {
            count++;
            leaf = leaf.next;
        }
        return count;
    }

    public Map<Long, StructuralCounters> drainStructuralCounters() {
        Map<Long, StructuralCounters> copy = Map.copyOf(structuralCounters);
        structuralCounters.clear();
        return copy;
    }

    public StructuralCounters consumeStructuralCounters(long regionId) {
        return structuralCounters.remove(regionId);
    }

    public RegionSnapshot snapshotRegion(long regionId) {
        InternalNode region = requireLeafLevelRegion(regionId);
        List<LeafNode> originalLeaves = leafChildren(region);
        List<long[]> keys = new ArrayList<>(originalLeaves.size());
        List<long[]> values = new ArrayList<>(originalLeaves.size());
        int[] sizes = new int[originalLeaves.size()];
        for (int i = 0; i < originalLeaves.size(); i++) {
            LeafNode leaf = originalLeaves.get(i);
            sizes[i] = leaf.size;
            keys.add(Arrays.copyOf(leaf.keys, leaf.keys.length));
            values.add(Arrays.copyOf(leaf.values, leaf.values.length));
        }
        LeafNode outsidePrev = originalLeaves.get(0).prev;
        LeafNode outsideNext = originalLeaves.get(originalLeaves.size() - 1).next;
        int originalRecordCount = 0;
        for (int v : sizes) originalRecordCount += v;
        return new RegionSnapshot(regionId, region, originalLeaves, keys, values, sizes,
                outsidePrev, outsideNext, originalRecordCount);
    }

    public void restoreRegion(RegionSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        InternalNode region = snapshot.region;
        if (region.id != snapshot.regionId) {
            throw new IllegalStateException("Snapshot does not match region");
        }
        if (!isAttachedToTree(region)) {
            throw new IllegalStateException("Region escaped its structural boundary; local rollback is unsafe");
        }

        int currentRecordCount = 0;
        for (int i = 0; i < region.childCount; i++) currentRecordCount += ((LeafNode) region.children[i]).size;
        long sizeCorrection = snapshot.originalRecordCount - currentRecordCount;
        LeafNode currentOutsidePrev = ((LeafNode) region.children[0]).prev;
        LeafNode currentOutsideNext = ((LeafNode) region.children[region.childCount - 1]).next;

        runWithoutStructuralCounters(() -> {
            List<LeafNode> originals = snapshot.originalLeaves;
            for (int i = 0; i < originals.size(); i++) {
                LeafNode leaf = originals.get(i);
                Arrays.fill(leaf.keys, 0L);
                Arrays.fill(leaf.values, 0L);
                System.arraycopy(snapshot.keys.get(i), 0, leaf.keys, 0, leaf.keys.length);
                System.arraycopy(snapshot.values.get(i), 0, leaf.values, 0, leaf.values.length);
                leaf.size = snapshot.sizes[i];
                leaf.parent = region;
            }

            Arrays.fill(region.children, null);
            region.childCount = originals.size();
            for (int i = 0; i < originals.size(); i++) {
                region.children[i] = originals.get(i);
            }
            relinkWindow(originals, currentOutsidePrev, currentOutsideNext);
            recomputeSeparators(region);
            refreshAncestors(region.parent);
        });
        size += sizeCorrection;
        refreshLeafLevelRegionMembership(region);
    }

    public void replayMutations(Collection<? extends Mutation> mutations) {
        runWithoutStructuralCounters(() -> {
            for (Mutation mutation : mutations) {
                mutation.replayOn(this);
            }
        });
    }

    /**
     * Returns false when the requested mutation could force the leaf-level region itself to split,
     * underflow, or collapse. LARS uses this before each mutating trial operation so that regional
     * rollback remains exact and does not need to snapshot the full tree.
     */
    public boolean canApplyMutationWithinRegion(long regionId, Mutation mutation) {
        InternalNode region = leafLevelRegionsById.get(regionId);
        if (region == null) return false;
        LeafNode leaf = findLeaf(mutation.key());
        if (leaf.parent != region) return false;

        if (mutation instanceof UpdateMutation) return true;

        if (mutation instanceof InsertMutation) {
            if (binarySearch(leaf.keys, leaf.size, mutation.key()) >= 0) return true;
            if (leaf.size < maxLeafKeys) return true;
            return region.childCount < maxInternalChildren;
        }

        if (mutation instanceof DeleteMutation) {
            int pos = binarySearch(leaf.keys, leaf.size, mutation.key());
            if (pos < 0) return true;
            if (leaf.size - 1 >= minLeafKeys) return true;

            int idx = childIndex(region, leaf);
            LeafNode left = idx > 0 ? (LeafNode) region.children[idx - 1] : null;
            LeafNode right = idx + 1 < region.childCount ? (LeafNode) region.children[idx + 1] : null;
            if ((left != null && left.size > minLeafKeys) ||
                    (right != null && right.size > minLeafKeys)) {
                return true;
            }

            int afterMergeChildren = region.childCount - 1;
            if (region == root) return afterMergeChildren >= 2;
            return afterMergeChildren >= minInternalChildren;
        }

        return false;
    }

    public ActionEstimate estimateAction(LocalAction action,
                                         long regionId,
                                         int fromLeafInclusive,
                                         int toLeafInclusive) {
        InternalNode region = requireLeafLevelRegion(regionId);
        validateWindow(region, fromLeafInclusive, toLeafInclusive);
        return switch (action) {
            case SLACKEN -> estimateSlacken(region, fromLeafInclusive, toLeafInclusive);
            case COMPACT -> estimateCompact(region, fromLeafInclusive, toLeafInclusive);
        };
    }

    public ActionResult applyAction(ActionEstimate estimate) {
        Objects.requireNonNull(estimate, "estimate");

        // Never trust a feasibility decision computed against an older tree state.
        // Re-estimate the exact requested action/window immediately before mutation.
        ActionEstimate current = estimateAction(
                estimate.action(),
                estimate.regionId(),
                estimate.fromLeafInclusive(),
                estimate.toLeafInclusive());
        if (!current.feasible()) {
            throw new IllegalArgumentException(
                    "Action estimate is stale or no longer feasible: " + current.reason());
        }

        InternalNode region = requireLeafLevelRegion(current.regionId());
        validateWindow(region, current.fromLeafInclusive(), current.toLeafInclusive());

        long start = System.nanoTime();
        runWithoutStructuralCounters(() -> {
            switch (current.action()) {
                case SLACKEN -> applySlacken(region, current.fromLeafInclusive(), current.toLeafInclusive());
                case COMPACT -> applyCompact(region, current.fromLeafInclusive(), current.toLeafInclusive());
            }
        });
        refreshLeafLevelRegionMembership(region);
        long elapsed = System.nanoTime() - start;
        return new ActionResult(current, elapsed);
    }

    public void validate() {
        if (root == null) throw new IllegalStateException("root is null");
        if (root.parent != null) throw new IllegalStateException("root has a parent");

        ValidationContext ctx = new ValidationContext();
        validateNode(root, 0, ctx, true);

        List<LeafNode> leaves = new ArrayList<>();
        collectLeaves(root, leaves);
        LeafNode chain = leftmostLeaf();
        int i = 0;
        LeafNode prev = null;
        long counted = 0;
        while (chain != null) {
            if (i >= leaves.size() || chain != leaves.get(i)) {
                throw new IllegalStateException("leaf link chain differs from tree traversal");
            }
            if (chain.prev != prev) throw new IllegalStateException("broken prev leaf link");
            counted += chain.size;
            prev = chain;
            chain = chain.next;
            i++;
        }
        if (i != leaves.size()) throw new IllegalStateException("leaf link chain is incomplete");
        if (counted != size) throw new IllegalStateException("size mismatch: field=" + size + ", counted=" + counted);
        validateLeafLevelRegionIndex();
    }

    public List<Entry> snapshotEntries() {
        List<Entry> out = new ArrayList<>((int) Math.min(Integer.MAX_VALUE, size));
        LeafNode leaf = leftmostLeaf();
        while (leaf != null) {
            for (int i = 0; i < leaf.size; i++) out.add(new Entry(leaf.keys[i], leaf.values[i]));
            leaf = leaf.next;
        }
        return List.copyOf(out);
    }

    // ---------------------------------------------------------------------
    // Local actions
    // ---------------------------------------------------------------------

    private ActionEstimate estimateSlacken(InternalNode region, int from, int to) {
        int oldCount = to - from + 1;
        if (region.childCount >= maxInternalChildren) {
            return infeasible(LocalAction.SLACKEN, region.id, from, to,
                    "region parent has no spare child slot");
        }
        int records = recordsInWindow(region, from, to);
        int newCount = oldCount + 1;
        if (records < newCount * minLeafKeys) {
            return infeasible(LocalAction.SLACKEN, region.id, from, to,
                    "insufficient records to preserve minimum leaf occupancy after adding a leaf");
        }
        if (records > newCount * maxLeafKeys) {
            return infeasible(LocalAction.SLACKEN, region.id, from, to,
                    "window cannot fit after slackening");
        }
        int moved = estimateMovedRecords(region, from, to, newCount);
        return new ActionEstimate(LocalAction.SLACKEN, region.id, from, to,
                records, moved, newCount, +1, true, "OK");
    }

    private ActionEstimate estimateCompact(InternalNode region, int from, int to) {
        int oldCount = to - from + 1;
        if (oldCount < 2) {
            return infeasible(LocalAction.COMPACT, region.id, from, to,
                    "compaction requires at least two leaves");
        }
        int records = recordsInWindow(region, from, to);
        int target = ceilDiv(records, maxLeafKeys);
        target = Math.max(1, target);
        if (target >= oldCount) {
            return infeasible(LocalAction.COMPACT, region.id, from, to,
                    "window is not sparse enough to remove a leaf");
        }
        if (target > 1 && records < target * minLeafKeys) {
            return infeasible(LocalAction.COMPACT, region.id, from, to,
                    "target layout violates minimum leaf occupancy");
        }

        int removed = oldCount - target;
        int afterChildren = region.childCount - removed;
        int minChildren = region == root ? 2 : minInternalChildren;
        if (afterChildren < minChildren) {
            return infeasible(LocalAction.COMPACT, region.id, from, to,
                    "compaction would underflow or collapse the region parent");
        }

        int moved = estimateMovedRecords(region, from, to, target);
        return new ActionEstimate(LocalAction.COMPACT, region.id, from, to,
                records, moved, oldCount, -removed, true, "OK");
    }

    private static ActionEstimate infeasible(LocalAction action, long regionId, int from, int to, String reason) {
        return new ActionEstimate(action, regionId, from, to, 0, 0, 0, 0, false, reason);
    }

    private void applySlacken(InternalNode region, int from, int to) {
        int oldCount = to - from + 1;
        List<Entry> entries = entriesInWindow(region, from, to);
        List<LeafNode> targets = new ArrayList<>(oldCount + 1);
        for (int i = from; i <= to; i++) targets.add((LeafNode) region.children[i]);
        targets.add(newLeaf());
        redistributeEntries(entries, targets);
        replaceChildWindow(region, from, to, targets);
    }

    private void applyCompact(InternalNode region, int from, int to) {
        int oldCount = to - from + 1;
        List<Entry> entries = entriesInWindow(region, from, to);
        int targetCount = Math.max(1, ceilDiv(entries.size(), maxLeafKeys));
        List<LeafNode> targets = new ArrayList<>(targetCount);
        for (int i = 0; i < targetCount; i++) targets.add((LeafNode) region.children[from + i]);
        redistributeEntries(entries, targets);
        replaceChildWindow(region, from, to, targets);
    }

    private void redistributeEntries(List<Entry> entries, List<LeafNode> targets) {
        int n = entries.size();
        int k = targets.size();
        int base = n / k;
        int extra = n % k;
        int cursor = 0;
        for (int i = 0; i < k; i++) {
            LeafNode leaf = targets.get(i);
            Arrays.fill(leaf.keys, 0L);
            Arrays.fill(leaf.values, 0L);
            int count = base + (i < extra ? 1 : 0);
            leaf.size = count;
            for (int j = 0; j < count; j++) {
                Entry e = entries.get(cursor++);
                leaf.keys[j] = e.key();
                leaf.values[j] = e.value();
            }
        }
    }

    private void replaceChildWindow(InternalNode region, int from, int to, List<LeafNode> replacements) {
        LeafNode outsidePrev = ((LeafNode) region.children[from]).prev;
        LeafNode outsideNext = ((LeafNode) region.children[to]).next;

        List<Node> newChildren = new ArrayList<>(region.childCount - (to - from + 1) + replacements.size());
        for (int i = 0; i < from; i++) newChildren.add(region.children[i]);
        newChildren.addAll(replacements);
        for (int i = to + 1; i < region.childCount; i++) newChildren.add(region.children[i]);

        Arrays.fill(region.children, null);
        region.childCount = newChildren.size();
        for (int i = 0; i < newChildren.size(); i++) {
            region.children[i] = newChildren.get(i);
            region.children[i].parent = region;
        }
        relinkWindow(replacements, outsidePrev, outsideNext);
        recomputeSeparators(region);
        refreshAncestors(region.parent);
    }

    private int estimateMovedRecords(InternalNode region, int from, int to, int targetLeafCount) {
        int oldLeafCount = to - from + 1;
        int records = 0;
        for (int i = from; i <= to; i++) records += ((LeafNode) region.children[i]).size;

        // Redistribution preserves key order and assigns the first target partition to the
        // first existing leaf, the second partition to the second leaf, and so on. Therefore
        // a record stays in place exactly when its old and new ordinal intervals overlap.
        // Counting those overlaps is identical to materializing an origin object per record,
        // but makes candidate enumeration allocation-free and O(number of repair leaves).
        int base = records / targetLeafCount;
        int extra = records % targetLeafCount;
        int oldStart = 0;
        int targetStart = 0;
        int unchanged = 0;
        for (int target = 0; target < targetLeafCount; target++) {
            int targetEnd = targetStart + base + (target < extra ? 1 : 0);
            if (target < oldLeafCount) {
                LeafNode oldLeaf = (LeafNode) region.children[from + target];
                int oldEnd = oldStart + oldLeaf.size;
                unchanged += Math.max(0, Math.min(oldEnd, targetEnd)
                        - Math.max(oldStart, targetStart));
                oldStart = oldEnd;
            }
            targetStart = targetEnd;
        }
        return records - unchanged;
    }

    // ---------------------------------------------------------------------
    // Core B+ tree mechanics
    // ---------------------------------------------------------------------

    private LeafNode newLeaf() { return new LeafNode(nextNodeId.getAndIncrement(), maxLeafKeys); }
    private InternalNode newInternal() { return new InternalNode(nextNodeId.getAndIncrement(), maxInternalChildren); }

    private LeafNode findLeaf(long key) {
        Node node = root;
        while (!node.isLeaf()) {
            InternalNode in = (InternalNode) node;
            int idx = 0;
            int keyCount = in.childCount - 1;
            while (idx < keyCount && key >= in.keys[idx]) idx++;
            node = in.children[idx];
        }
        return (LeafNode) node;
    }

    private static int binarySearch(long[] keys, int size, long key) {
        int lo = 0, hi = size - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long v = keys[mid];
            if (v < key) lo = mid + 1;
            else if (v > key) hi = mid - 1;
            else return mid;
        }
        return -(lo + 1);
    }

    private static int lowerBound(long[] keys, int size, long key) {
        int lo = 0, hi = size;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (keys[mid] < key) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    private void insertIntoLeafAt(LeafNode leaf, int pos, long key, long value) {
        int move = leaf.size - pos;
        if (move > 0) {
            System.arraycopy(leaf.keys, pos, leaf.keys, pos + 1, move);
            System.arraycopy(leaf.values, pos, leaf.values, pos + 1, move);
        }
        leaf.keys[pos] = key;
        leaf.values[pos] = value;
        leaf.size++;
    }

    private void removeFromLeafAt(LeafNode leaf, int pos) {
        int move = leaf.size - pos - 1;
        if (move > 0) {
            System.arraycopy(leaf.keys, pos + 1, leaf.keys, pos, move);
            System.arraycopy(leaf.values, pos + 1, leaf.values, pos, move);
        }
        leaf.size--;
        leaf.keys[leaf.size] = 0L;
        leaf.values[leaf.size] = 0L;
    }

    private void splitLeaf(LeafNode leaf) {
        LeafNode right = newLeaf();
        int leftSize = leaf.size / 2;
        int rightSize = leaf.size - leftSize;
        System.arraycopy(leaf.keys, leftSize, right.keys, 0, rightSize);
        System.arraycopy(leaf.values, leftSize, right.values, 0, rightSize);
        Arrays.fill(leaf.keys, leftSize, leaf.size, 0L);
        Arrays.fill(leaf.values, leftSize, leaf.size, 0L);
        leaf.size = leftSize;
        right.size = rightSize;

        right.next = leaf.next;
        if (right.next != null) right.next.prev = right;
        right.prev = leaf;
        leaf.next = right;

        InternalNode parent = leaf.parent;
        long regionId = parent == null ? -1L : parent.id;
        incrementSplit(regionId);

        if (parent == null) {
            InternalNode newRoot = newInternal();
            newRoot.children[0] = leaf;
            newRoot.children[1] = right;
            newRoot.childCount = 2;
            leaf.parent = newRoot;
            right.parent = newRoot;
            recomputeSeparators(newRoot);
            root = newRoot;
            refreshLeafLevelRegionMembership(newRoot);
            return;
        }

        int idx = childIndex(parent, leaf);
        insertChildAt(parent, idx + 1, right);
        if (parent.childCount > maxInternalChildren) splitInternal(parent);
        else {
            refreshAncestors(parent);
            refreshLeafLevelRegionMembership(parent);
        }
    }

    private void splitInternal(InternalNode node) {
        InternalNode right = newInternal();
        int leftCount = node.childCount / 2;
        int rightCount = node.childCount - leftCount;
        for (int i = 0; i < rightCount; i++) {
            Node child = node.children[leftCount + i];
            right.children[i] = child;
            child.parent = right;
            node.children[leftCount + i] = null;
        }
        node.childCount = leftCount;
        right.childCount = rightCount;
        recomputeSeparators(node);
        recomputeSeparators(right);

        InternalNode parent = node.parent;
        if (parent == null) {
            InternalNode newRoot = newInternal();
            newRoot.children[0] = node;
            newRoot.children[1] = right;
            newRoot.childCount = 2;
            node.parent = newRoot;
            right.parent = newRoot;
            recomputeSeparators(newRoot);
            root = newRoot;
            refreshLeafLevelRegionMembership(node);
            refreshLeafLevelRegionMembership(right);
            refreshLeafLevelRegionMembership(newRoot);
            return;
        }

        int idx = childIndex(parent, node);
        insertChildAt(parent, idx + 1, right);
        refreshLeafLevelRegionMembership(node);
        refreshLeafLevelRegionMembership(right);
        if (parent.childCount > maxInternalChildren) splitInternal(parent);
        else {
            refreshAncestors(parent);
            refreshLeafLevelRegionMembership(parent);
        }
    }

    private void rebalanceLeaf(LeafNode leaf) {
        InternalNode parent = leaf.parent;
        int idx = childIndex(parent, leaf);
        LeafNode left = idx > 0 ? (LeafNode) parent.children[idx - 1] : null;
        LeafNode right = idx + 1 < parent.childCount ? (LeafNode) parent.children[idx + 1] : null;

        if (left != null && left.size > minLeafKeys) {
            System.arraycopy(leaf.keys, 0, leaf.keys, 1, leaf.size);
            System.arraycopy(leaf.values, 0, leaf.values, 1, leaf.size);
            leaf.keys[0] = left.keys[left.size - 1];
            leaf.values[0] = left.values[left.size - 1];
            leaf.size++;
            left.size--;
            left.keys[left.size] = 0L;
            left.values[left.size] = 0L;
            recomputeSeparators(parent);
            refreshAncestors(parent.parent);
            return;
        }

        if (right != null && right.size > minLeafKeys) {
            leaf.keys[leaf.size] = right.keys[0];
            leaf.values[leaf.size] = right.values[0];
            leaf.size++;
            removeFromLeafAt(right, 0);
            recomputeSeparators(parent);
            refreshAncestors(parent.parent);
            return;
        }

        if (left != null) {
            mergeLeaves(left, leaf, parent, idx);
        } else if (right != null) {
            mergeLeaves(leaf, right, parent, idx + 1);
        } else {
            throw new IllegalStateException("leaf has no sibling");
        }
    }

    private void mergeLeaves(LeafNode left, LeafNode right, InternalNode parent, int rightIndex) {
        if (left.size + right.size > maxLeafKeys) {
            throw new IllegalStateException("leaf merge overflow");
        }
        System.arraycopy(right.keys, 0, left.keys, left.size, right.size);
        System.arraycopy(right.values, 0, left.values, left.size, right.size);
        left.size += right.size;
        left.next = right.next;
        if (right.next != null) right.next.prev = left;
        incrementMerge(parent.id);
        removeChildAt(parent, rightIndex);
        handleInternalAfterChildRemoval(parent);
    }

    private void handleInternalAfterChildRemoval(InternalNode node) {
        if (node == root) {
            if (node.childCount == 1) {
                leafLevelRegionsById.remove(node.id);
                root = node.children[0];
                root.parent = null;
                if (!root.isLeaf()) refreshLeafLevelRegionMembership((InternalNode) root);
            } else {
                recomputeSeparators(node);
                refreshLeafLevelRegionMembership(node);
            }
            return;
        }

        if (node.childCount >= minInternalChildren) {
            recomputeSeparators(node);
            refreshAncestors(node.parent);
            refreshLeafLevelRegionMembership(node);
            return;
        }
        rebalanceInternal(node);
    }

    private void rebalanceInternal(InternalNode node) {
        InternalNode parent = node.parent;
        int idx = childIndex(parent, node);
        InternalNode left = idx > 0 && !parent.children[idx - 1].isLeaf()
                ? (InternalNode) parent.children[idx - 1] : null;
        InternalNode right = idx + 1 < parent.childCount && !parent.children[idx + 1].isLeaf()
                ? (InternalNode) parent.children[idx + 1] : null;

        if (left != null && left.childCount > minInternalChildren) {
            System.arraycopy(node.children, 0, node.children, 1, node.childCount);
            Node borrowed = left.children[left.childCount - 1];
            left.children[left.childCount - 1] = null;
            left.childCount--;
            node.children[0] = borrowed;
            borrowed.parent = node;
            node.childCount++;
            recomputeSeparators(left);
            recomputeSeparators(node);
            recomputeSeparators(parent);
            refreshAncestors(parent.parent);
            refreshLeafLevelRegionMembership(left);
            refreshLeafLevelRegionMembership(node);
            return;
        }

        if (right != null && right.childCount > minInternalChildren) {
            Node borrowed = right.children[0];
            node.children[node.childCount++] = borrowed;
            borrowed.parent = node;
            removeChildAtOnly(right, 0);
            recomputeSeparators(right);
            recomputeSeparators(node);
            recomputeSeparators(parent);
            refreshAncestors(parent.parent);
            refreshLeafLevelRegionMembership(right);
            refreshLeafLevelRegionMembership(node);
            return;
        }

        if (left != null) {
            mergeInternal(left, node, parent, idx);
        } else if (right != null) {
            mergeInternal(node, right, parent, idx + 1);
        } else {
            throw new IllegalStateException("internal node has no compatible sibling");
        }
    }

    private void mergeInternal(InternalNode left, InternalNode right, InternalNode parent, int rightIndex) {
        if (left.childCount + right.childCount > maxInternalChildren) {
            throw new IllegalStateException("internal merge overflow");
        }
        for (int i = 0; i < right.childCount; i++) {
            Node child = right.children[i];
            left.children[left.childCount++] = child;
            child.parent = left;
        }
        recomputeSeparators(left);
        removeChildAt(parent, rightIndex);
        leafLevelRegionsById.remove(right.id);
        refreshLeafLevelRegionMembership(left);
        handleInternalAfterChildRemoval(parent);
    }

    private void insertChildAt(InternalNode parent, int index, Node child) {
        int move = parent.childCount - index;
        if (move > 0) System.arraycopy(parent.children, index, parent.children, index + 1, move);
        parent.children[index] = child;
        child.parent = parent;
        parent.childCount++;
        recomputeSeparators(parent);
    }

    private void removeChildAt(InternalNode parent, int index) {
        removeChildAtOnly(parent, index);
        recomputeSeparators(parent);
    }

    private void removeChildAtOnly(InternalNode parent, int index) {
        int move = parent.childCount - index - 1;
        if (move > 0) System.arraycopy(parent.children, index + 1, parent.children, index, move);
        parent.childCount--;
        parent.children[parent.childCount] = null;
    }

    private void recomputeSeparators(InternalNode node) {
        Arrays.fill(node.keys, 0L);
        for (int i = 1; i < node.childCount; i++) node.keys[i - 1] = node.children[i].firstKey();
    }

    private void refreshAncestors(InternalNode node) {
        while (node != null) {
            recomputeSeparators(node);
            node = node.parent;
        }
    }

    // ---------------------------------------------------------------------
    // Region helpers
    // ---------------------------------------------------------------------

    private void collectRegions(Node node, List<RegionView> out) {
        if (node.isLeaf()) return;
        InternalNode in = (InternalNode) node;
        if (isLeafLevelRegion(in)) {
            out.add(toRegionView(in));
            return;
        }
        for (int i = 0; i < in.childCount; i++) collectRegions(in.children[i], out);
    }

    private RegionView toRegionView(InternalNode region) {
        List<LeafView> leaves = new ArrayList<>(region.childCount);
        int records = 0;
        double minOcc = Double.POSITIVE_INFINITY;
        for (int i = 0; i < region.childCount; i++) {
            LeafNode leaf = (LeafNode) region.children[i];
            records += leaf.size;
            double occ = ((double) leaf.size) / maxLeafKeys;
            minOcc = Math.min(minOcc, occ);
            long first = leaf.size == 0 ? Long.MIN_VALUE : leaf.keys[0];
            long last = leaf.size == 0 ? Long.MIN_VALUE : leaf.keys[leaf.size - 1];
            leaves.add(new LeafView(leaf.id, i, leaf.size, maxLeafKeys, first, last));
        }
        double avg = region.childCount == 0 ? 0.0 : ((double) records) / (region.childCount * maxLeafKeys);
        long first = region.childCount == 0 ? Long.MIN_VALUE : ((LeafNode) region.children[0]).firstKey();
        LeafNode lastLeaf = region.childCount == 0 ? null : (LeafNode) region.children[region.childCount - 1];
        long last = lastLeaf == null || lastLeaf.size == 0 ? Long.MIN_VALUE : lastLeaf.keys[lastLeaf.size - 1];
        boolean canRemove = region == root ? region.childCount - 1 >= 2
                : region.childCount - 1 >= minInternalChildren;
        return new RegionView(region.id, region.childCount, records, maxLeafKeys,
                avg, minOcc == Double.POSITIVE_INFINITY ? 0.0 : minOcc,
                first, last, region.childCount < maxInternalChildren,
                canRemove, List.copyOf(leaves));
    }

    private InternalNode requireLeafLevelRegion(long regionId) {
        InternalNode region = leafLevelRegionsById.get(regionId);
        if (region == null) {
            throw new IllegalArgumentException("No active leaf-level region with id " + regionId);
        }
        return region;
    }

    private static boolean isLeafLevelRegion(InternalNode node) {
        if (node.childCount == 0) return false;
        for (int i = 0; i < node.childCount; i++) if (!node.children[i].isLeaf()) return false;
        return true;
    }

    private void rebuildLeafLevelRegionIndex() {
        leafLevelRegionsById.clear();
        collectLeafLevelRegionNodes(root, leafLevelRegionsById);
    }

    private static void collectLeafLevelRegionNodes(Node node, Map<Long, InternalNode> out) {
        if (node.isLeaf()) return;
        InternalNode in = (InternalNode) node;
        if (isLeafLevelRegion(in)) {
            out.put(in.id, in);
            return;
        }
        for (int i = 0; i < in.childCount; i++) collectLeafLevelRegionNodes(in.children[i], out);
    }

    /**
     * Maintains the leaf-level region registry incrementally at structural mutation boundaries.
     * Ordinary lookups stay O(1), while full-tree reconstruction is reserved for bulk-load and
     * validation paths.
     */
    private void refreshLeafLevelRegionMembership(InternalNode node) {
        if (node == null) return;
        if (isAttachedToTree(node) && isLeafLevelRegion(node)) {
            leafLevelRegionsById.put(node.id, node);
        } else {
            leafLevelRegionsById.remove(node.id);
        }
    }

    private void validateLeafLevelRegionIndex() {
        HashMap<Long, InternalNode> actual = new HashMap<>();
        collectLeafLevelRegionNodes(root, actual);
        if (!actual.keySet().equals(leafLevelRegionsById.keySet())) {
            throw new IllegalStateException("leaf-level region index keys are stale");
        }
        for (Map.Entry<Long, InternalNode> e : actual.entrySet()) {
            if (leafLevelRegionsById.get(e.getKey()) != e.getValue()) {
                throw new IllegalStateException("leaf-level region index points at a stale node");
            }
        }
    }

    private boolean isAttachedToTree(Node candidate) {
        if (candidate == root) return true;
        InternalNode p = candidate.parent;
        if (p == null) return false;
        for (int i = 0; i < p.childCount; i++) if (p.children[i] == candidate) return true;
        return false;
    }

    private static int childIndex(InternalNode parent, Node child) {
        for (int i = 0; i < parent.childCount; i++) if (parent.children[i] == child) return i;
        throw new IllegalStateException("child is not attached to parent");
    }

    private List<LeafNode> leafChildren(InternalNode region) {
        List<LeafNode> leaves = new ArrayList<>(region.childCount);
        for (int i = 0; i < region.childCount; i++) leaves.add((LeafNode) region.children[i]);
        return leaves;
    }

    private void validateWindow(InternalNode region, int from, int to) {
        if (from < 0 || to < from || to >= region.childCount) {
            throw new IllegalArgumentException("Invalid leaf window [" + from + ", " + to + "] for region with " + region.childCount + " leaves");
        }
    }

    private int recordsInWindow(InternalNode region, int from, int to) {
        int records = 0;
        for (int i = from; i <= to; i++) records += ((LeafNode) region.children[i]).size;
        return records;
    }

    private List<Entry> entriesInWindow(InternalNode region, int from, int to) {
        List<Entry> entries = new ArrayList<>(recordsInWindow(region, from, to));
        for (int i = from; i <= to; i++) {
            LeafNode leaf = (LeafNode) region.children[i];
            for (int j = 0; j < leaf.size; j++) entries.add(new Entry(leaf.keys[j], leaf.values[j]));
        }
        return entries;
    }

    private static void relinkWindow(List<LeafNode> leaves, LeafNode outsidePrev, LeafNode outsideNext) {
        for (int i = 0; i < leaves.size(); i++) {
            LeafNode leaf = leaves.get(i);
            leaf.prev = i == 0 ? outsidePrev : leaves.get(i - 1);
            leaf.next = i + 1 == leaves.size() ? outsideNext : leaves.get(i + 1);
        }
        if (outsidePrev != null) outsidePrev.next = leaves.get(0);
        if (outsideNext != null) outsideNext.prev = leaves.get(leaves.size() - 1);
    }

    private LeafNode leftmostLeaf() {
        Node n = root;
        while (!n.isLeaf()) n = ((InternalNode) n).children[0];
        return (LeafNode) n;
    }

    // ---------------------------------------------------------------------
    // Validation
    // ---------------------------------------------------------------------

    private static final class ValidationContext {
        Integer leafDepth;
    }

    private record Bounds(long min, long max, boolean empty) {}

    private Bounds validateNode(Node node, int depth, ValidationContext ctx, boolean isRoot) {
        if (node.isLeaf()) {
            LeafNode leaf = (LeafNode) node;
            if (!isRoot && (leaf.size < minLeafKeys || leaf.size > maxLeafKeys)) {
                throw new IllegalStateException("leaf occupancy violation at node " + leaf.id + ": " + leaf.size);
            }
            if (isRoot && leaf.size > maxLeafKeys) throw new IllegalStateException("root leaf overflow");
            for (int i = 1; i < leaf.size; i++) {
                if (leaf.keys[i - 1] >= leaf.keys[i]) throw new IllegalStateException("leaf keys not strictly sorted");
            }
            if (ctx.leafDepth == null) ctx.leafDepth = depth;
            else if (ctx.leafDepth != depth) throw new IllegalStateException("leaves at different depths");
            if (leaf.size == 0) return new Bounds(0, 0, true);
            return new Bounds(leaf.keys[0], leaf.keys[leaf.size - 1], false);
        }

        InternalNode in = (InternalNode) node;
        int minChildren = isRoot ? 2 : minInternalChildren;
        if (in.childCount < minChildren || in.childCount > maxInternalChildren) {
            throw new IllegalStateException("internal occupancy violation at node " + in.id + ": " + in.childCount);
        }

        Bounds first = null;
        Bounds previous = null;
        Bounds last = null;
        for (int i = 0; i < in.childCount; i++) {
            Node child = in.children[i];
            if (child == null) throw new IllegalStateException("null child inside active range");
            if (child.parent != in) throw new IllegalStateException("wrong parent pointer");
            Bounds b = validateNode(child, depth + 1, ctx, false);
            if (!b.empty()) {
                if (previous != null && !previous.empty() && previous.max() >= b.min()) {
                    throw new IllegalStateException("overlapping child key ranges");
                }
                if (i > 0 && in.keys[i - 1] != b.min()) {
                    throw new IllegalStateException("separator mismatch at internal node " + in.id);
                }
                if (first == null) first = b;
                previous = b;
                last = b;
            }
        }
        if (first == null) return new Bounds(0, 0, true);
        return new Bounds(first.min(), last.max(), false);
    }

    private void collectLeaves(Node node, List<LeafNode> out) {
        if (node.isLeaf()) {
            out.add((LeafNode) node);
            return;
        }
        InternalNode in = (InternalNode) node;
        for (int i = 0; i < in.childCount; i++) collectLeaves(in.children[i], out);
    }

    // ---------------------------------------------------------------------
    // Misc
    // ---------------------------------------------------------------------

    private void incrementSplit(long regionId) {
        if (suppressStructuralCounters || regionId < 0) return;
        StructuralCounters old = structuralCounters.getOrDefault(regionId, new StructuralCounters(0, 0));
        structuralCounters.put(regionId, new StructuralCounters(old.leafSplits + 1, old.leafMerges));
    }

    private void incrementMerge(long regionId) {
        if (suppressStructuralCounters || regionId < 0) return;
        StructuralCounters old = structuralCounters.getOrDefault(regionId, new StructuralCounters(0, 0));
        structuralCounters.put(regionId, new StructuralCounters(old.leafSplits, old.leafMerges + 1));
    }

    private void runWithoutStructuralCounters(Runnable task) {
        boolean old = suppressStructuralCounters;
        suppressStructuralCounters = true;
        try { task.run(); }
        finally { suppressStructuralCounters = old; }
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }
}
