package us.sosia.video.stream.handler;

import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.handler.codec.oneone.OneToOneDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import us.sosia.video.stream.handler.frame.FrameDecoder;

import com.xuggle.ferry.IBuffer;
import com.xuggle.xuggler.IAudioSamples;
import com.xuggle.xuggler.ICodec;
import com.xuggle.xuggler.IPacket;
import com.xuggle.xuggler.IPixelFormat;
import com.xuggle.xuggler.IStreamCoder;
import com.xuggle.xuggler.IStreamCoder.Direction;
import com.xuggle.xuggler.IVideoPicture;
import com.xuggle.xuggler.video.ConverterFactory;
import com.xuggle.xuggler.video.ConverterFactory.Type;
import com.xuggle.xuggler.video.IConverter;
/**
 * This codec will encode the bufferedImage to h264 stream
 * **/
public class H264StreamDecoder extends OneToOneDecoder{
    protected final static Logger logger = LoggerFactory.getLogger(H264StreamDecoder.class);
    protected final IStreamCoder iStreamCoder = IStreamCoder.make(Direction.DECODING, ICodec.ID.CODEC_ID_H264);
    protected final IStreamCoder iAudioStreamCoder = IStreamCoder.make(Direction.DECODING, ICodec.ID.CODEC_ID_AAC);
    protected final Type type = ConverterFactory.findRegisteredConverter(ConverterFactory.XUGGLER_BGR_24);
    protected final StreamFrameListener streamFrameListener;
    protected final Dimension dimension;
	
    protected final FrameDecoder frameDecoder;
    protected final ExecutorService decodeWorker ;
    /**
     * 
     * Cause there may be one or more image in the frame,so we need an Stream listener here to get all the image
     * 
     * */
    public H264StreamDecoder(StreamFrameListener streamFrameListener,
                             Dimension dimension,boolean internalFrameDecoder,boolean decodeInOtherThread) {
        super();
        this.streamFrameListener = streamFrameListener;
        this.dimension = dimension;
        if (internalFrameDecoder) {
            frameDecoder = new FrameDecoder(4);
        }else {
            frameDecoder = null;
        }
        if (decodeInOtherThread) {
            decodeWorker = Executors.newSingleThreadExecutor(); 
        }else {
            decodeWorker = null;
        }
		
        initialize();
    }

    private void initialize(){
        iStreamCoder.open(null, null);
        iAudioStreamCoder.open(null, null);
    }

    @Override
        protected Object decode(ChannelHandlerContext ctx, Channel channel,
                                final Object msg) throws Exception {
        if (decodeWorker != null) {
            decodeWorker.execute(new decodeTask(msg));
            return null;
        }
        else {
            if (msg == null) {
                throw new NullPointerException("you cannot pass into an null to the decode");
            }
            ChannelBuffer frameBuffer;
            if (frameDecoder != null) {
                frameBuffer = frameDecoder.decode((ChannelBuffer)msg);
                if (frameBuffer == null) {
                    return null;
                }

            }
            else {
                frameBuffer = (ChannelBuffer)msg;
            }

            int size = frameBuffer.readableBytes();
            logger.info("decode the frame size :{}",size);
			
            //start to decode
            IBuffer iBuffer = IBuffer.make(null, size);
            IPacket iPacket = IPacket.make(iBuffer);
            iPacket.getByteBuffer().put(frameBuffer.toByteBuffer());
            //decode the packet
            if (!iPacket.isComplete()) {
                return null;
            }
            logger.info("packet stream index: " + iPacket.getFlags());
			
            if ( iPacket.getByteBuffer().get() != -1 ) {
                IVideoPicture picture = IVideoPicture.make(IPixelFormat.Type.YUV420P,
                                                           dimension.width, dimension.height);
                try {
                    // decode the packet into the video picture
                    int postion = 0;
                    int packageSize = iPacket.getSize();
                    while(postion < packageSize){
                        postion+= iStreamCoder.decodeVideo(picture, iPacket, postion);
                        if (postion < 0)
                            throw new RuntimeException("error "
                                                       + " decoding video");
                        // if this is a complete picture, dispatch the picture
                        if (picture.isComplete()){
                            IConverter  converter = ConverterFactory.createConverter(type
                                                                                     .getDescriptor(), picture);
                            BufferedImage image = converter.toImage(picture);
                            //BufferedImage convertedImage = ImageUtils.convertToType(image, BufferedImage.TYPE_3BYTE_BGR);
                            //here ,put out the image 
                            if (streamFrameListener != null) {
                                streamFrameListener.onFrameReceived(image);
                            }
                            converter.delete();	
                        }
                        else {
                            picture.delete();
                            iPacket.delete();
                            return null;
                        }
                        //clean the picture and reuse it
                        picture.getByteBuffer().clear();
                    }
                } finally {
                    if (picture != null)
                        picture.delete();
                    iPacket.delete();
                    // ByteBufferUtil.destroy(data);
                }	
            }
            else {
                IAudioSamples samples = IAudioSamples.make(1024, 1);
		        
                /*
                 * A packet can actually contain multiple sets of samples (or frames of samples
                 * in audio-decoding speak).  So, we may need to call decode audio multiple
                 * times at different offsets in the packet's data.  We capture that here.
                 */
                int offset = 0;
		        
                /*
                 * Keep going until we've processed all data
                 */
                while(offset < iPacket.getSize()) {
                    int bytesDecoded = iAudioStreamCoder.decodeAudio(samples, iPacket, offset);
                    if (bytesDecoded < 0)
                        throw new RuntimeException("got error decoding audio in stream");
		          
                    offset += bytesDecoded;
		          
                    /*
                     * Some decoder will consume data in a packet, but will not be able to construct
                     * a full set of samples yet.  Therefore you should always check if you
                     * got a complete set of samples from the decoder
                     */
                    if (samples.isComplete()) {
                        if (streamFrameListener != null) {
                            streamFrameListener.onAudioRecieved(samples);
                        }
                    }
                }
            }
            return null;	
        }
    }
	
	
    private class decodeTask implements Runnable{
        private final Object msg;
		
        public decodeTask(Object msg) {
            super();
            this.msg = msg;
        }


        @Override
            public void run() {
            if (msg == null) {
                return;
            }
            ChannelBuffer frameBuffer;
            if (frameDecoder != null) {
                try {
                    frameBuffer = frameDecoder.decode((ChannelBuffer)msg);
                    if (frameBuffer == null) {
                        return ;
                    }
                } catch (Exception e) {
                    return;
                }

            }
            else {
                frameBuffer = (ChannelBuffer)msg;
            }
			
            int size = frameBuffer.readableBytes();
            logger.info("decode the frame size :{}",size);
            //start to decode
            IBuffer iBuffer = IBuffer.make(null, size);
            IPacket iPacket = IPacket.make(iBuffer);
            iPacket.getByteBuffer().put(frameBuffer.toByteBuffer());
            //decode the packet
            if (!iPacket.isComplete()) {
                return ;
            }
            logger.info("packet stream index: " + iPacket.getStreamIndex());
            if ( iPacket.getStreamIndex() == 0  ) {
                IVideoPicture picture = IVideoPicture.make(IPixelFormat.Type.YUV420P,
                                                           dimension.width, dimension.height);
                try {
                    // decode the packet into the video picture
                    int postion = 0;
                    int packageSize = iPacket.getSize();
                    while(postion < packageSize){
                        postion+= iStreamCoder.decodeVideo(picture, iPacket, postion);
                        if (postion < 0) {
                            throw new RuntimeException("error "
                                                       + " decoding video");
                        }
                        // if this is a complete picture, dispatch the picture
                        if (picture.isComplete()){
                            IConverter  converter = ConverterFactory.createConverter(type
                                                                                     .getDescriptor(), picture);
                            BufferedImage image = converter.toImage(picture);
                            //BufferedImage convertedImage = ImageUtils.convertToType(image, BufferedImage.TYPE_3BYTE_BGR);
                            //here ,put out the image 
                            if (streamFrameListener != null) {
                                streamFrameListener.onFrameReceived(image);
                            }
                            converter.delete();	
                        }
                        else {
                            picture.delete();
                            iPacket.delete();
                            return ;
                        }
                        //clean the picture and reuse it
                        picture.getByteBuffer().clear();
                    }
                } finally {
                    if (picture != null) {
                        picture.delete();
                    }
                    iPacket.delete();
                }	
                return ;
            }
            else {
                iPacket.delete();
                logger.info("got audio frame");
            }
        }
		
    }
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	

}
