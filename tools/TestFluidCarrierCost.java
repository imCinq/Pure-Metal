import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.launch.knot.Knot;
import net.fabricmc.fabric.api.client.render.fluid.v1.*;
import net.fabricmc.fabric.impl.client.rendering.fluid.FluidRenderingImpl;
import net.minecraft.client.renderer.block.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import java.lang.management.ManagementFactory;
import com.sun.management.ThreadMXBean;

public class TestFluidCarrierCost {
 public static void main(String[] args)throws Exception {
  var loader=new Knot(EnvType.CLIENT).init(new String[]{"--gameDir",args[0]});
  Class.forName("TestFluidCarrierCost$Checks",true,loader).getMethod("run").invoke(null);
 }
 public static class Checks {
  static volatile Object sink;
  static volatile int pick;
  static final Object BUILDER=new Object(),COLLECTOR=new Object();
  static final FluidRendering.DefaultRenderer DEFAULT=makeDefault();
  static FluidRendering.DefaultRenderer makeDefault() {
   if(Boolean.getBoolean("fluidProbe.fallback"))return new FluidRendering.DefaultRenderer(){};
   try {var c=Class.forName("net.caffeinemc.mods.sodium.fabric.render.FluidRendererImpl$DefaultRenderContext").getDeclaredConstructor();c.setAccessible(true);return (FluidRendering.DefaultRenderer)c.newInstance();}
   catch(Exception e){throw new AssertionError(e);}
  }
  static final FluidRenderHandler HANDLER=new FluidRenderHandler(){
   @Override public void renderFluid(FluidRenderer renderer,BlockPos pos,BlockAndTintGetter world,FluidRenderer.Output output,BlockState state,FluidState fluid){output.getBuilder(null);}
  };
  static final RuntimeException FAILURE=new RuntimeException("expected vanilla failure");
  static class Recorder extends FluidRenderer {
   int depth;
   boolean fail;
   Recorder(){super(null);}
   @Override public void tesselate(BlockAndTintGetter world,BlockPos pos,FluidRenderer.Output output,BlockState state,FluidState fluid){
    if(!FluidRenderingImpl.IS_RENDERING_VANILLA_DEFAULT.isBound()
       || FluidRenderingImpl.IS_RENDERING_VANILLA_DEFAULT.get()!=null)throw new AssertionError("Vanilla default scope lost");
    if(fail)throw FAILURE;
    if(depth++<2)FluidRenderingImpl.renderVanillaDefault(this,world,pos,output,state,fluid);
    depth--;
    output.getBuilder(null);
   }
  }
  static void checkVanilla(){
   var renderer=new Recorder();
   FluidRenderer.Output output=layer->{sink=BUILDER;return null;};
   FluidRenderingImpl.renderVanillaDefault(renderer,null,null,output,null,null);
   if(FluidRenderingImpl.IS_RENDERING_VANILLA_DEFAULT.isBound())throw new AssertionError("Vanilla nested scope escaped");
   renderer.fail=true;
   try{FluidRenderingImpl.renderVanillaDefault(renderer,null,null,output,null,null);throw new AssertionError("Failure swallowed");}
   catch(RuntimeException actual){if(actual!=FAILURE)throw actual;}
   if(FluidRenderingImpl.IS_RENDERING_VANILLA_DEFAULT.isBound())throw new AssertionError("Vanilla failed scope escaped");
   System.out.println("PASS: actual vanilla-default cached scope, recursive calls and exception cleanup");
  }
  static void one(Object builder,Object collector){
   FluidRenderer.Output output=layer->{sink=pick==0?builder:collector;return null;};
   FluidRenderingImpl.render(null,HANDLER,null,null,output,null,null,DEFAULT);
  }
  public static void run()throws Exception {
   boolean disabled=Boolean.getBoolean("puremetal.disableFluidCallbacks");
   long bridges=java.util.Arrays.stream(FluidRenderingImpl.class.getDeclaredFields()).filter(m->m.getName().startsWith("puremetal$fluidCallback")).count();
   if(bridges!=(disabled?0:1))throw new AssertionError("wrong actual Fabric variant: "+bridges);
   checkVanilla();
   System.out.println("FIXTURE caller="+(Boolean.getBoolean("fluidProbe.fallback")?"fallback":"sodium-owner"));
   // Give C2 time to optimize the actual caller, without forcing either callback to escape.
   for(int round=0;round<10;round++) for(int i=0;i<1000000;i++)one(BUILDER,COLLECTOR);
   var bean=(ThreadMXBean)ManagementFactory.getThreadMXBean();
   bean.setThreadAllocatedMemoryEnabled(true);
   long id=Thread.currentThread().threadId();
   for(int round=0;round<7;round++){
    long a=bean.getThreadAllocatedBytes(id),t=System.nanoTime();
    for(int i=0;i<1000000;i++)one(BUILDER,COLLECTOR);
    long ns=System.nanoTime()-t,n=bean.getThreadAllocatedBytes(id)-a;
    long limit=disabled || Boolean.getBoolean("fluidProbe.fallback") ? 56 : 24;
    if(n>limit*1000000L+4096)throw new AssertionError("Fluid caller allocation regression: "+n/1000000.0+" bytes/call");
    System.out.printf("ACTUAL-FABRIC mode=%s round=%d ns/call=%.3f allocatedBytes/call=%.3f%n",disabled?"original":"reused",round,ns/1000000.0,n/1000000.0);
   }
  }
 }
}
