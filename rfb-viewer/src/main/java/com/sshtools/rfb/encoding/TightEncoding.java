package com.sshtools.rfb.encoding;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sshtools.rfb.ProtocolEngine;
import com.sshtools.rfb.RFBDisplay;
import com.sshtools.rfb.RFBDisplayModel;
import com.sshtools.rfbcommon.ImageUtil;
import com.sshtools.rfbcommon.ProtocolReader;
import com.sshtools.rfbcommon.RFBConstants;

public class TightEncoding extends AbstractRawEncoding {
	final static Logger LOG = LoggerFactory.getLogger(ProtocolEngine.class);

	private final static int OP_FILL = 0x08;
	private final static int OP_JPEG = 0x09;

	private final static int OP_FILTER_RAW = 0x00;
	private final static int OP_FILTER_PALETTE = 0x01;
	private final static int OP_FILTER_GRADIENT = 0x02;

	private final static int NO_OF_INFLATERS = 4;

	private final static int MASK_FILTER = 0x40;
	private final static int MASK_STREAM = 0x30;

	private ProtocolReader input;
	private Inflater[] zlibInflaters = new Inflater[NO_OF_INFLATERS];
	private RFBDisplayModel rfbModel;
	private int streamId;
	private RFBDisplay display;
	private byte[] buffer;
	private int[] palette24 = new int[256];
	private int numberOfColors;
	private int pixSize;
	private boolean tightNative;
	private byte[] colorBuff;

	public TightEncoding() {
	}

	@Override
	public int getType() {
		return 7;
	}

	@Override
	public void processEncodedRect(RFBDisplay display, int x, int y, int width,
			int height, int encodingType) throws IOException {
		this.display = display;

		input = display.getEngine().getInputStream();
		rfbModel = display.getDisplayModel();
		pixSize = (rfbModel.getColorDepth() == 24 && rfbModel.getBitsPerPixel() == 32) ? 3
				: rfbModel.getBytesPerPixel();
		tightNative = rfbModel.getBytesPerPixel() == 4 && pixSize == 3
				&& rfbModel.getRedMax() == 0xff
				&& rfbModel.getGreenMax() == 0xff
				&& rfbModel.getBlueMax() == 0xff;
		colorBuff = new byte[rfbModel.getBytesPerPixel()];

		// Get the op and reset compression
		int op = input.readUnsignedByte();
		resetZlib(op);
		int type = op >> 4 & 0x0F;

		// Handle primary op
		switch (type) {
		case OP_FILL:
			doFill(x, y, width, height);
			break;
		case OP_JPEG:
			doJpeg(x, y);
			break;
		default:
			doTight(x, y, width, height, op);
			break;
		}

		display.requestRepaint(display.getContext().getScreenUpdateTimeout(),
				x, y, width, height);
	}

	@Override
	public String getName() {
		return "Tight";
	}

	@Override
	public boolean isPseudoEncoding() {
		return false;
	}

	private void doTight(int x, int y, int width, int height, int op)
			throws IOException {
		if (LOG.isDebugEnabled()) {
			LOG.debug("Tight " + x + "," + y + "," + width + "," + height);
		}

		// Reset variables and extract stream ID for compression (if any)

		streamId = (op & MASK_STREAM) >> 4;
		numberOfColors = 0;

		// Extract the filter being using
		int filter = 0;
		if ((op & MASK_FILTER) > 0) {
			filter = input.readUnsignedByte();
		}

		synchronized (rfbModel.getLock()) {
			switch (filter) {
			case OP_FILTER_RAW:
				if (LOG.isDebugEnabled()) {
					LOG.info("Raw");
				}
				buffer = readTight(pixSize * width * height);
				if (tightNative) {
					doProcessRawTight(x, y, width, height);
				} else {
					doProcessRaw(display, x, y, width, height, buffer);
				}
				break;
			case OP_FILTER_PALETTE:
				numberOfColors = input.readUnsignedByte() + 1;
				if (LOG.isDebugEnabled()) {
					LOG.debug("Palette of " + numberOfColors);
				}
				for (int i = 0; i < numberOfColors; ++i) {
					palette24[i] = readTightColor();
				}
				buffer = readTight(numberOfColors == 2 ? height
						* ((width + 7) / 8) : width * height);

				if (numberOfColors == 2) {
					decodeMonoData(x, y, width, height, buffer, palette24);
				} else {
					int i = 0;
					for (int decodeY = y; decodeY < y + height; decodeY++) {
						for (int decodeX = x; decodeX < x + width; decodeX++) {
							rfbModel.getImageBuffer().setRGB(decodeX, decodeY,
									palette24[buffer[i++] & 0xFF]);
						}
					}
				}

				break;
			case OP_FILTER_GRADIENT:
				LOG.info("Gradient");
				buffer = readTight(pixSize * width * height);
				decodeGradientData(x, y, width, height, buffer);
				break;
			default:
				break;
			}
		}
	}

	private void doProcessRawTight(int x, int y, int width, int height) {
		ColorSpace cs = ColorSpace.getInstance(ColorSpace.CS_sRGB);
		int[] nBits = { 8, 8, 8 };
		int[] bOffs = { 0, 1, 2 };
		ColorModel colorModel = new ComponentColorModel(cs, nBits, false,
				false, Transparency.OPAQUE, DataBuffer.TYPE_BYTE);
		Raster raster = Raster.createInterleavedRaster(DataBuffer.TYPE_BYTE,
				width, height, width * 3, 3, bOffs, null);
		BufferedImage img = new BufferedImage(colorModel,
				(WritableRaster) raster, false, null);
		decodeIntoImage(buffer, rfbModel, img, 0);
		rfbModel.drawRectangle(x, y, width, height, img);
	}

	private void doJpeg(int x, int y) throws IOException {
		byte[] imageData = new byte[input.readCompactLen()];
		input.readFully(imageData);
		BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageData));
		if (LOG.isDebugEnabled()) {
			LOG.debug("JPEG " + x + "," + y + "," + image.getWidth() + ","
					+ image.getHeight());
		}
		rfbModel.drawRectangle(x, y, image.getWidth(), image.getHeight(), image);
	}

	private void doFill(final int x, final int y, final int width,
			final int height) throws IOException {
		final Color color = new Color(readTightColor());
		if (LOG.isDebugEnabled()) {
			LOG.debug("Fill " + x + "," + y + "," + width + "," + height
					+ " with " + color);
		}
		SwingUtilities.invokeLater(new Runnable() {
			@Override
			public void run() {
				Graphics g = rfbModel.getGraphicBuffer();
				g.setColor(color);
				g.fillRect(x, y, width, height);
			}
		});
	}

	private byte[] readTight(int len) throws IOException {
		if (len < RFBConstants.TIGHT_MIN_BYTES_TO_COMPRESS) {
			if (LOG.isTraceEnabled()) {
				LOG.trace("Uncompress " + len + " bytes");
			}
			byte[] buffer = new byte[len];
			input.readFully(buffer);
			return buffer;
		} else {
			if (LOG.isTraceEnabled()) {
				LOG.trace("Compressed " + len + " bytes");
			}
			return readZlib(len);
		}
	}

	private byte[] readZlib(int len) throws IOException {
		int raw = input.readCompactLen2();
		byte[] buffer = new byte[len + raw];
		input.readFully(buffer, len, raw);
		if (null == zlibInflaters[streamId]) {
			zlibInflaters[streamId] = new Inflater();
		}
		Inflater decoder = zlibInflaters[streamId];
		decoder.setInput(buffer, len, raw);
		try {
			decoder.inflate(buffer, 0, len);
			if (LOG.isTraceEnabled()) {
				LOG.trace("Decompressed from " + raw + " to " + len);
			}
		} catch (DataFormatException e) {
			throw new IOException(e);
		}
		return buffer;
	}

	private void decodeGradientData(int x, int y, int w, int h, byte[] buf) {
		int dx, dy, c;
		byte[] prevRow = new byte[w * 3];
		byte[] thisRow = new byte[w * 3];
		byte[] pix = new byte[3];
		int[] est = new int[3];
		for (dy = 0; dy < h; dy++) {
			for (c = 0; c < 3; c++) {
				pix[c] = (byte) (prevRow[c] + buf[dy * w * 3 + c]);
				thisRow[c] = pix[c];
			}
			rfbModel.getImageBuffer().setRGB(
					x,
					dy + y,
					(pix[0] & 0xFF) << 16 | (pix[1] & 0xFF) << 8
							| (pix[2] & 0xFF));
			for (dx = 1; dx < w; dx++) {
				for (c = 0; c < 3; c++) {
					est[c] = ((prevRow[dx * 3 + c] & 0xFF) + (pix[c] & 0xFF) - (prevRow[(dx - 1)
							* 3 + c] & 0xFF));
					if (est[c] > 0xFF) {
						est[c] = 0xFF;
					} else if (est[c] < 0x00) {
						est[c] = 0x00;
					}
					pix[c] = (byte) (est[c] + buf[(dy * w + dx) * 3 + c]);
					thisRow[dx * 3 + c] = pix[c];
				}
				rfbModel.getImageBuffer().setRGB(
						x + dx,
						y + dy,
						(pix[0] & 0xFF) << 16 | (pix[1] & 0xFF) << 8
								| (pix[2] & 0xFF));
			}
			System.arraycopy(thisRow, 0, prevRow, 0, w * 3);
		}
	}

	private void decodeMonoData(int x, int y, int w, int h, byte[] src,
			int[] palette) {
		int dx, dy, n;
		int i;
		int rowBytes = (w + 7) / 8;
		byte b;
		for (dy = 0; dy < h; dy++) {
			for (dx = 0; dx < w / 8; dx++) {
				b = src[dy * rowBytes + dx];
				for (n = 7; n >= 0; n--) {
					rfbModel.getImageBuffer().setRGB(dx + x, dy + y,
							palette[b >> n & 1]);
				}
			}
			i = x;
			for (n = 7; n >= 8 - w % 8; n--) {
				rfbModel.getImageBuffer().setRGB(i++, dy + y,
						palette[src[dy * rowBytes + dx] >> n & 1]);
			}
		}
	}

	private void resetZlib(int op) {
		for (int i = 0; i < NO_OF_INFLATERS; ++i) {
			if ((op & 1) != 0 && zlibInflaters[i] != null) {
				zlibInflaters[i].reset();
			}
			op >>= 1;
		}

	}

	private int readTightColor() throws IOException {
		input.readFully(colorBuff, 0, pixSize);
		if (tightNative) {
			return ImageUtil.translate((colorBuff[0] & 0xff) << 16
					| (colorBuff[1] & 0xff) << 8 | colorBuff[2] & 0xff,
					rfbModel);
		} else {
			int decodeAndUntranslatePixel = ImageUtil
					.decodeAndUntranslatePixel(colorBuff, 0, rfbModel);
			return ImageUtil.translate(decodeAndUntranslatePixel, rfbModel);
		}
	}
}
