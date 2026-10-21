package android.graphics;
import java.awt.image.BufferedImage;
public final class Bitmap {
 public final BufferedImage image;public Bitmap(BufferedImage image){this.image=image;}
 public int getWidth(){return image.getWidth();}public int getHeight(){return image.getHeight();}public void recycle(){}
 public static Bitmap createBitmap(Bitmap source,int x,int y,int w,int h,Matrix matrix,boolean filter){BufferedImage out=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);for(int row=0;row<h;row++)for(int col=0;col<w;col++)out.setRGB(col,row,source.image.getRGB(x+col,y+(matrix.sy<0?h-1-row:row)));return new Bitmap(out);}
}
