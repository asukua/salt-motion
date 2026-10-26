package dev.saltmotion.artwork;

/** Validated native output geometry; stride and allocation sizes are checked before native calls. */
public record FrameLayout(int width,int height,int rowBytes,int stride,int packedBytes,int bufferBytes) {
    public static final int MAX_SIDE=4096;
    public static FrameLayout of(int width,int height) {
        if(width<1 || height<1 || width>MAX_SIDE || height>MAX_SIDE)
            throw new IllegalArgumentException("Unsupported native frame dimensions: "+width+"x"+height);
        int row=Math.multiplyExact(width,4);
        int stride=(Math.addExact(row,63)/64)*64;
        return new FrameLayout(width,height,row,stride,Math.multiplyExact(row,height),Math.multiplyExact(stride,height));
    }
}
