package android.graphics;
import java.io.*;import javax.imageio.ImageIO;
public final class BitmapFactory {public static class Options {public boolean inScaled;}public static Bitmap decodeStream(InputStream stream,Rect rect,Options options){try{return new Bitmap(ImageIO.read(stream));}catch(IOException ex){throw new IllegalStateException(ex);}}}
