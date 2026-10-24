import io.github.imcinq.puremetal.perf.MeshBounds;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

public final class TestMeshBounds {
    static void require(boolean v) {if(!v) throw new AssertionError();}
    public static void main(String[] args) {
        var data=ByteBuffer.allocate(80).order(ByteOrder.nativeOrder());data.position(8);data.limit(72);
        data.putFloat(12,-4).putFloat(16,17).putFloat(20,33);
        data.putFloat(44,19).putFloat(48,-12).putFloat(52,1);
        var bounds=MeshBounds.scan(data,2,32,4,false);
        require(bounds!=null && bounds.minX()<-4 && bounds.maxX()>19 && bounds.minY()<-12 && bounds.maxY()>17 && bounds.maxZ()>33);
        require(data.position()==8 && data.limit()==72 && data.order()==ByteOrder.nativeOrder());
        data.putFloat(12,Float.NaN);require(MeshBounds.scan(data,2,32,4,false)==null);
        require(MeshBounds.scan(data,3,32,4,false)==null && MeshBounds.scan(data,2,32,30,false)==null && MeshBounds.scan(data,0,32,0,false)==null);
        var random=new Random(123);var packed=ByteBuffer.allocate(20*10000).order(ByteOrder.nativeOrder());
        float[] xs=new float[10000],ys=new float[10000],zs=new float[10000];
        for(int i=0;i<xs.length;i++) {
            int x=random.nextInt(1<<20),y=random.nextInt(1<<20),z=random.nextInt(1<<20);
            int hi=(x>>10)|((y>>10)<<10)|((z>>10)<<20),lo=(x&1023)|((y&1023)<<10)|((z&1023)<<20);
            packed.putInt(i*20,hi).putInt(i*20+4,lo);
            xs[i]=x/32768f-8;ys[i]=y/32768f-8;zs[i]=z/32768f-8;
            require(MeshBounds.decode(hi,lo,0)==xs[i] && MeshBounds.decode(hi,lo,10)==ys[i] && MeshBounds.decode(hi,lo,20)==zs[i]);
        }
        var p=MeshBounds.scan(packed,xs.length,20,0,true);require(p!=null);
        for(int i=0;i<xs.length;i++) require(xs[i]>=p.minX() && xs[i]<=p.maxX() && ys[i]>=p.minY() && ys[i]<=p.maxY() && zs[i]>=p.minZ() && zs[i]<=p.maxZ());
        require(MeshBounds.decode(0,0,0)==-8 && MeshBounds.decode(-1,-1,20)<24);
        System.out.println("PASS mesh bounds: actual decoded positions, custom extents, nonfinite/truncated fallback, buffer lifetime, 10000 packed vertices");
    }
}
