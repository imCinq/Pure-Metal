package io.github.imcinq.puremetal.perf;

import org.jspecify.annotations.Nullable;

/** Diagnostic projection for the stock two-matrix vertex path, conventional0..1 depth, Metal's vertex-Y flip. */
public final class BoundsProjection {
    public record Query(int x0,int y0,int x1,int y1,float nearest) {}
    private BoundsProjection() {}
    public static @Nullable Query project(MeshBounds box, float[] model, float[] projection,
        float tx,float ty,float tz,int width,int height) {
        return project(box,model,projection,tx,ty,tz,width,height,0);
    }
    public static @Nullable Query project(MeshBounds box, float[] model, float[] projection,
        float tx,float ty,float tz,int width,int height,float translationRoundoff) {
        return project(box,model,projection,tx,ty,tz,width,height,translationRoundoff,false);
    }
    public static @Nullable Query project(MeshBounds box, float[] model, float[] projection,
        float tx,float ty,float tz,int width,int height,float translationRoundoff,boolean reverse) {
        if(!Float.isFinite(translationRoundoff) || translationRoundoff<0) return null;
        if (width<=0 || height<=0 || model.length!=16 || projection.length!=16) return null;
        double minX=Double.POSITIVE_INFINITY,minY=minX,minZ=minX,maxX=Double.NEGATIVE_INFINITY,maxY=maxX;
        float[] translation={tx,ty,tz};
        for (int corner=0;corner<8;corner++) {
            double[] vertex={((corner&1)==0?box.minX():box.maxX()),((corner&2)==0?box.minY():box.maxY()),
                ((corner&4)==0?box.minZ():box.maxZ()),1};
            double[] error=new double[4];
            for(int axis=0;axis<3;axis++) {
                vertex[axis]+=translation[axis];error[axis]=2*Math.ulp((float)vertex[axis])+translationRoundoff;
                if(!Double.isFinite(vertex[axis]) || !Double.isFinite(error[axis])) return null;
            }
            double[][] viewed=transform(model,vertex,error),clip=transform(projection,viewed[0],viewed[1]);
            double[] v=clip[0],e=clip[1];double wLow=v[3]-e[3],wHigh=v[3]+e[3];
            // Near/far plane intersections, behind camera, extreme/uncertain transforms stay visible.
            if(!Double.isFinite(wLow) || wLow<=.001 || v[2]-e[2]<=0 || v[2]+e[2]>=wLow) return null;
            for(int axis=0;axis<2;axis++) {
                double a=(v[axis]-e[axis])/wLow,b=(v[axis]-e[axis])/wHigh,
                    c=(v[axis]+e[axis])/wLow,d=(v[axis]+e[axis])/wHigh;
                double low=Math.min(Math.min(a,b),Math.min(c,d)),high=Math.max(Math.max(a,b),Math.max(c,d));
                if(!Double.isFinite(low) || !Double.isFinite(high)) return null;
                if(axis==0) {minX=Math.min(minX,low);maxX=Math.max(maxX,high);}
                else {minY=Math.min(minY,low);maxY=Math.max(maxY,high);}
            }
            minZ=Math.min(minZ,reverse?1-(v[2]+e[2])/wLow:(v[2]-e[2])/wHigh);
        }
        // SPIRV-Cross flips vertex Y; positive original clip Y maps to increasing target row here.
        int x0=(int)Math.max(0,Math.floor((minX+1)*width*.5)-1),y0=(int)Math.max(0,Math.floor((minY+1)*height*.5)-1);
        int x1=(int)Math.min(width,Math.ceil((maxX+1)*width*.5)+1),y1=(int)Math.min(height,Math.ceil((maxY+1)*height*.5)+1);
        float nearest=Math.nextDown((float)minZ);
        if(x0>=x1 || y0>=y1 || !Float.isFinite(nearest) || nearest<=0 || nearest>=1) return null;
        return new Query(x0,y0,x1,y1,nearest);
    }
    private static double[][] transform(float[] matrix,double[] vector,double[] inputError) {
        double[] value=new double[4],error=new double[4];
        for(int row=0;row<4;row++) {
            double magnitude=0;
            for(int col=0;col<4;col++) {
                double coefficient=matrix[col*4+row];
                value[row]+=coefficient*vector[col];magnitude+=Math.abs(coefficient*vector[col]);
                error[row]+=Math.abs(coefficient)*inputError[col];
            }
            // Margin for the two float matrix-vector operations; supports FMA and separate products/adds.
            error[row]+=magnitude*32*0x1.0p-23+Double.MIN_NORMAL;
        }
        return new double[][]{value,error};
    }
}
