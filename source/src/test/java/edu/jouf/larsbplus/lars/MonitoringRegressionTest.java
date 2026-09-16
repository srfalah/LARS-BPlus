package edu.jouf.larsbplus.lars;
import edu.jouf.larsbplus.core.BPlusTree;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MonitoringRegressionTest {
    private BPlusTree.RegionView region() {
        return new BPlusTree.RegionView(1, 2, 96, 64, .75, .75, 0, 95, true, true, List.of());
    }
    @Test void emptyRegionalTailDoesNotHaveUnitWeight() {
        var acc = new RegionWindowAccumulator(1);
        acc.recordRange(100, 2, 100);
        acc.recordRange(-1, 1, 0);
        var stats = acc.finish(1, region(), new BPlusTree.StructuralCounters(0,0));
        assertEquals(.03, stats.averageScanAmplification(), 1e-12);
        assertEquals(2, stats.counts().get(OperationType.RANGE_SEARCH));
        assertEquals(1, stats.latencySampleCount(OperationType.RANGE_SEARCH));
        assertEquals(100, stats.overallP99Nanos());
        acc.reset();
        acc.recordRange(200, 4, 100);
        assertEquals(.04, acc.finish(2,region(),null).averageScanAmplification(),1e-12);
        assertEquals(.03, stats.averageScanAmplification(),1e-12);
    }
    @Test void physicalFragmentationRemainsVisibleAndZeroDenominatorsAreDefined() {
        var acc = new RegionWindowAccumulator(1);
        acc.recordRange(100,2,96);
        double baseline=acc.finish(1,region(),null).averageScanAmplification();
        acc.reset(); acc.recordRange(100,3,96);
        assertEquals(1.5,acc.finish(2,region(),null).averageScanAmplification()/baseline,1e-12);
        acc.reset(); acc.recordRange(-1,2,0);
        assertEquals(2,acc.finish(3,region(),null).averageScanAmplification());
        acc.reset(); assertTrue(Double.isNaN(acc.finish(4,region(),null).averageScanAmplification()));
    }
    @Test void samplingSelectsExactlyTheOriginalTypeSpecificOperations() throws Exception {
        var index = new LarsBPlusIndex(new BPlusTree(),LarsConfig.pilotDefaults());
        Method sample=LarsBPlusIndex.class.getDeclaredMethod("shouldSampleLatency",OperationType.class);
        sample.setAccessible(true);
        long[] counts=new long[OperationType.values().length];
        Random random=new Random(1664525);
        for(int i=0;i<100000;i++) {
            OperationType t=OperationType.values()[random.nextInt(5)];
            int stride=t==OperationType.INSERT||t==OperationType.RANGE_SEARCH?8:32;
            assertEquals(counts[t.ordinal()]++%stride==0,sample.invoke(index,t));
        }
    }
    @Test void activeTrialDoesNotForceGlobalLatencyTimingAndSearchIsStillTrialWork() throws Exception {
        var tree=new BPlusTree(8,8);
        for(int i=0;i<2000;i++) tree.insert(i,i);
        long regionId=-1;
        BPlusTree.RegionView view=null;
        outer: for(var r:tree.regions()) {
            for(int width=2;width<=Math.min(6,r.leafCount());width++) {
                for(int from=0;from+width<=r.leafCount();from++) {
                    if(tree.estimateAction(BPlusTree.LocalAction.SLACKEN,r.regionId(),from,from+width-1).feasible()) {
                        regionId=r.regionId();view=r;break outer;
                    }
                }
            }
        }
        assertTrue(regionId>=0);
        LarsConfig d=LarsConfig.pilotDefaults();
        LarsConfig c=new LarsConfig(64,2,2,5,8,.2,.10,.10,.01,2,6,1,1,
                100000,1,1,1,32,64,.02,1,1,1,.01,
                d.insertLatencyWeight(),d.splitRateWeight(),d.headroomWeight(),
                d.scanLatencyWeight(),d.scanAmplificationWeight(),d.sparsityWeight());
        var index=new LarsBPlusIndex(tree,c);
        for(int i=0;i<2;i++) index.controller().onObservationWindow(insertStats(regionId,i+1,view,1000,0));
        for(int i=0;i<2;i++) index.controller().onObservationWindow(insertStats(regionId,100+i,view,5000,20));
        assertTrue(index.controller().hasActiveTrial());
        Method sample=LarsBPlusIndex.class.getDeclaredMethod("shouldSampleLatency",OperationType.class);
        sample.setAccessible(true);
        int timed=0;
        for(int i=0;i<64;i++) if((boolean)sample.invoke(index,OperationType.SEARCH)) timed++;
        assertEquals(2,timed,"an active local trial must preserve the configured SEARCH stride");
        long before=index.runtimeCounters().trialOperations();
        index.search(view.firstKey());
        assertEquals(before+1,index.runtimeCounters().trialOperations());
    }

    private static RegionWindowStats insertStats(long regionId,long seq,BPlusTree.RegionView view,double latency,long splits) {
        var counts=new EnumMap<OperationType,Integer>(OperationType.class);
        var p50=new EnumMap<OperationType,Double>(OperationType.class);
        var p95=new EnumMap<OperationType,Double>(OperationType.class);
        var p99=new EnumMap<OperationType,Double>(OperationType.class);
        for(var t:OperationType.values()) counts.put(t,0);
        counts.put(OperationType.INSERT,64);
        p50.put(OperationType.INSERT,latency*.7);p95.put(OperationType.INSERT,latency);p99.put(OperationType.INSERT,latency*1.1);
        return new RegionWindowStats(regionId,seq,64,counts,counts,p50,p95,p99,latency*1.1,Double.NaN,splits,0,
                view.averageOccupancy(),view.minimumOccupancy(),view.leafCount(),view.recordCount());
    }

}
