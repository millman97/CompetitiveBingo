package com.millman97.competitivebingo.capture;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import org.junit.Test;
import static org.junit.Assert.*;

public class ImageEncoderTest
{
	@Test
	public void largeTransparentFramesBecomeReadableBoundedJpegs() throws Exception
	{
		final ImageEncoder.Encoded encoded = ImageEncoder.encode(
			new BufferedImage(2560, 1440, BufferedImage.TYPE_INT_ARGB));
		assertNotNull(encoded);
		assertEquals("image/jpeg", encoded.getMediaType());
		assertTrue(encoded.getBytes().length <= ImageEncoder.TARGET_BYTES);
		final BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(encoded.getBytes()));
		assertNotNull(decoded);
		assertTrue(decoded.getWidth() <= ImageEncoder.MAX_WIDTH);
		assertEquals(decoded.getWidth() * 9, decoded.getHeight() * 16);
	}
}
