import io.github.imcinq.puremetal.perf.BoundsProjection;
import io.github.imcinq.puremetal.perf.MeshBounds;
import java.util.Random;

/** Compare the diagnostic enclosure against independently evaluated float vertex transforms. */
public final class TestBoundsProjection {
    static void require(boolean v) {if(!v) throw new AssertionError();}
    static float[] transform(float[] m,float[] v) {
        float[] out=new float[4];
        for(int row=0;row<4;row++) for(int col=0;col<4;col++) out[row]+=m[col*4+row]*v[col];
        return out;
    }
    public static void main(String[] args) {
        float[] identity={1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};
        var box=new MeshBounds(-.2f,-.3f,.2f,.4f,.5f,.7f);
        var q=BoundsProjection.project(box,identity,identity,0,0,0,800,600);
        require(q!=null && q.x0()<320 && q.x1()>560 && q.y0()<210 && q.y1()>450 && q.nearest()<.2f);
        var reversed=BoundsProjection.project(box,identity,identity,0,0,0,800,600,0,true);
        require(reversed!=null && reversed.nearest()<.3f);
        require(BoundsProjection.project(box,identity,identity,0,0,-.5f,800,600)==null);
        require(BoundsProjection.project(box,identity,identity,0,0,.5f,800,600)==null);
        require(BoundsProjection.project(box,identity,identity,Float.NaN,0,0,800,600)==null);
        // Shader (local + regionOffset) + sectionOffset can cancel large operands after rounding.
        var cancellation=BoundsProjection.project(box,identity,identity,0,0,0,800,600,8*Math.ulp(8193f));
        require(cancellation!=null && cancellation.nearest()<(.2f-4096f)+4096f);
        var random=new Random(125);int accepted=0,checked=0;
        for(int fixture=0;fixture<10000;fixture++) {
            float[] model=identity.clone(),projection=identity.clone();
            float angle=random.nextFloat()*6.28f,c=(float)Math.cos(angle),s=(float)Math.sin(angle);
            model[0]=c;model[1]=s;model[4]=-s;model[5]=c;
            // Perspective with w=-z, conventional 0..1 depth, camera looking down negative Z.
            projection[0]=1.4f;projection[5]=1.8f;projection[10]=-1.001f;projection[11]=-1;projection[14]=-.1f;projection[15]=0;
            float tx=(random.nextFloat()-.5f)*40,ty=(random.nextFloat()-.5f)*40,tz=-2-random.nextFloat()*80;
            MeshBounds b=new MeshBounds(-.5f,-.5f,-.5f,.5f,.5f,.5f);
            var query=BoundsProjection.project(b,model,projection,tx,ty,tz,1920,1080);
            if(query==null) continue;accepted++;
            var reverseQuery=BoundsProjection.project(b,model,projection,tx,ty,tz,1920,1080,0,true);
            require(reverseQuery!=null);
            for(int point=0;point<32;point++) {
                float x=point<8?((point&1)==0?-.5f:.5f):random.nextFloat()-.5f;
                float y=point<8?((point&2)==0?-.5f:.5f):random.nextFloat()-.5f;
                float z=point<8?((point&4)==0?-.5f:.5f):random.nextFloat()-.5f;
                float[] clip=transform(projection,transform(model,new float[]{x+tx,y+ty,z+tz,1}));
                double px=(clip[0]/clip[3]+1)*960,py=(clip[1]/clip[3]+1)*540;
                if(px>=0 && px<1920 && py>=0 && py<1080)
                    require(px>=query.x0() && px<query.x1() && py>=query.y0() && py<query.y1() && clip[2]/clip[3]>=query.nearest()
                        && 1-clip[2]/clip[3]>=reverseQuery.nearest());
                checked++;
            }
        }
        require(accepted>1000 && checked>32000);
        System.out.println("PASS diagnostic projection: "+checked+" float-transformed points enclosed, plane/nonfinite fallback. Live uniform and Metal orientation validation remains separate.");
    }
}
