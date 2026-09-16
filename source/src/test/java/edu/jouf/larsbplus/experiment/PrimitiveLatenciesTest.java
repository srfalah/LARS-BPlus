package edu.jouf.larsbplus.experiment;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class PrimitiveLatenciesTest {
    @Test void exactQuantilesMatchExistingHarnessIncludingAfterAppend() {
        var primitive=new PrimitiveLatencies();
        var boxed=new ArrayList<Long>();
        assertTrue(Double.isNaN(primitive.percentile(.99)));
        var random=new Random(1664525);
        for(int i=0;i<500;i++) {
            long v=random.nextInt(10000); primitive.add(v);boxed.add(v);
            for(double q:new double[]{.5,.95,.99}) assertEquals(CsvUtil.percentile(boxed,q),primitive.percentile(q));
            assertEquals(CsvUtil.mean(boxed),primitive.mean());
        }
    }
}
