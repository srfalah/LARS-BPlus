package edu.jouf.larsbplus.core;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PointSearchResultTest {
    @Test void reusableSearchMatchesImmutableTraceIncludingMissesAndMutation() {
        var tree=new BPlusTree(8,8);
        var result=new BPlusTree.PointSearchResult();
        tree.searchInto(0,result);
        assertFalse(result.found());assertEquals(-1,result.regionId());
        for(int i=0;i<1000;i++)tree.insert(i*2L,i==0?Long.MIN_VALUE:i);
        for(int pass=0;pass<2;pass++) {
            for(long key=-1;key<=2000;key++) {
                var expected=tree.searchWithRegion(key);
                tree.searchInto(key,result);
                assertEquals(expected.regionId(),result.regionId());
                assertEquals(expected.value().isPresent(),result.found());
                if(result.found())assertEquals(expected.value().getAsLong(),result.value());
            }
            for(int i=0;i<400;i++)tree.delete(i*2L);
        }
        tree.validate();
    }
}
