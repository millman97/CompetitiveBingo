package com.millman97.competitivebingo.capture;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Iterator;
import javax.annotation.Nullable;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

/**
 * Turns a captured frame into bytes small enough to upload.
 *
 * Uses JPEG at decreasing quality and width.
 */
@Slf4j
public final class ImageEncoder
{
	static final int MAX_WIDTH = 1280;
	static final int TARGET_BYTES = 200 * 1024;
	private static final float[] QUALITIES = {0.8f, 0.65f, 0.5f, 0.4f};

	/** Widths to try, as a fraction of {@link #MAX_WIDTH}, when quality alone cannot fit. */
	private static final double[] SCALE_LADDER = {1.0, 0.75, 0.5};

	private ImageEncoder()
	{
	}

	@Value
	public static class Encoded
	{
		byte[] bytes;

		/** What to send as Content-Type, e.g. "image/jpeg". */
		String mediaType;

		String base64;

		public Encoded(byte[] bytes, String mediaType)
		{
			this.bytes = bytes;
			this.mediaType = mediaType;
			this.base64 = Base64.getEncoder().encodeToString(bytes);
		}

		/** The form the image travels in, inside the event envelope. */
		public String base64()
		{
			return base64;
		}
	}

	/**
	 * @return the encoded image, or null if nothing usable could be produced
	 */
	@Nullable
	public static Encoded encode(Image frame)
	{
		final BufferedImage source = toBuffered(frame);
		if (source == null)
		{
			return null;
		}

		final BufferedImage scaled = downscale(source, MAX_WIDTH);

		Encoded smallest = null;

		for (double scale : SCALE_LADDER)
		{
			final BufferedImage candidate = scale >= 1.0
				? scaled
				: downscale(scaled, Math.max(1, (int) Math.round(scaled.getWidth() * scale)));

			final Encoded encoded = tryJpeg(candidate);
			if (encoded == null)
			{
				return smallest;
			}

			if (encoded.getBytes().length <= TARGET_BYTES)
			{
				return encoded;
			}

			if (smallest == null || encoded.getBytes().length < smallest.getBytes().length)
			{
				smallest = encoded;
			}
		}

		if (smallest != null)
		{
			log.debug("Could not reach the {} byte target; sending {} bytes",
				TARGET_BYTES, smallest.getBytes().length);
		}
		return smallest;
	}

	@Nullable
	private static BufferedImage toBuffered(Image frame)
	{
		if (frame instanceof BufferedImage)
		{
			return withoutAlpha((BufferedImage) frame);
		}

		final int width = frame.getWidth(null);
		final int height = frame.getHeight(null);
		if (width <= 0 || height <= 0)
		{
			log.debug("Captured frame has no usable size ({}x{})", width, height);
			return null;
		}

		final BufferedImage copy = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = copy.createGraphics();
		g.drawImage(frame, 0, 0, null);
		g.dispose();
		return copy;
	}

	/** JPEG cannot carry an alpha channel, and writing one produces a corrupt file. */
	private static BufferedImage withoutAlpha(BufferedImage source)
	{
		if (source.getType() == BufferedImage.TYPE_INT_RGB)
		{
			return source;
		}

		final BufferedImage opaque = new BufferedImage(source.getWidth(), source.getHeight(),
			BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = opaque.createGraphics();
		g.drawImage(source, 0, 0, null);
		g.dispose();
		return opaque;
	}

	static BufferedImage downscale(BufferedImage source, int maxWidth)
	{
		if (source.getWidth() <= maxWidth)
		{
			return source;
		}

		final double scale = (double) maxWidth / source.getWidth();
		final int height = Math.max(1, (int) Math.round(source.getHeight() * scale));

		final BufferedImage scaled = new BufferedImage(maxWidth, height, BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = scaled.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.drawImage(source, 0, 0, maxWidth, height, null);
		g.dispose();
		return scaled;
	}

	/**
	 * Encodes at decreasing quality until the result fits the target, then accepts whatever the
	 * lowest quality produced.
	 */
	@Nullable
	private static Encoded tryJpeg(BufferedImage image)
	{
		byte[] best = null;

		for (float quality : QUALITIES)
		{
			final byte[] bytes = writeJpeg(image, quality);
			if (bytes == null)
			{
				return null;
			}

			best = bytes;
			if (bytes.length <= TARGET_BYTES)
			{
				log.debug("Encoded {} bytes of JPEG at quality {}", bytes.length, quality);
				return new Encoded(bytes, "image/jpeg");
			}
		}

		return best == null ? null : new Encoded(best, "image/jpeg");
	}

	@Nullable
	private static byte[] writeJpeg(BufferedImage image, float quality)
	{
		final Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
		if (!writers.hasNext())
		{
			// Every Java SE runtime ships a JPEG writer, so this means something is very wrong.
			log.error("No JPEG writer available; screenshots cannot be encoded");
			return null;
		}

		final ImageWriter writer = writers.next();
		final ByteArrayOutputStream out = new ByteArrayOutputStream();

		try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(out))
		{
			writer.setOutput(stream);

			final ImageWriteParam params = writer.getDefaultWriteParam();
			if (params.canWriteCompressed())
			{
				params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
				params.setCompressionQuality(quality);
			}

			writer.write(null, new IIOImage(image, null, null), params);
			stream.flush();
			return out.toByteArray();
		}
		catch (IOException e)
		{
			log.warn("Could not encode screenshot", e);
			return null;
		}
		finally
		{
			writer.dispose();
		}
	}
}
