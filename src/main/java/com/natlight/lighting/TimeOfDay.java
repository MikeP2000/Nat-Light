/*
 * Copyright (c) 2026, MikeP2000
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.natlight.lighting;

/**
 * Computes the sun/moon light, ambient light and sky tint for a point in the day/night cycle.
 * <p>
 * The cycle is described by a sun angle in [0, 2pi): [0, pi] is day (sunrise in the east at 0,
 * noon at pi/2, sunset in the west at pi) and (pi, 2pi) is night, when the moon takes over.
 * World space follows the client: +x is east, +z is north and -y is up.
 */
public class TimeOfDay
{
	private static final float[] NOON_LIGHT = {1.00f, 0.96f, 0.88f};
	private static final float[] GOLDEN_LIGHT = {1.00f, 0.62f, 0.35f};
	private static final float[] MOON_LIGHT = {0.60f, 0.70f, 1.00f};

	private static final float[] DAY_AMBIENT = {0.80f, 0.82f, 0.86f};
	private static final float[] DUSK_AMBIENT = {0.72f, 0.60f, 0.60f};
	private static final float[] NIGHT_AMBIENT = {0.30f, 0.36f, 0.52f};

	private static final float[] DUSK_SKY = {0.95f, 0.58f, 0.42f};
	private static final float[] NIGHT_SKY = {0.04f, 0.06f, 0.13f};

	// How far south the sun and moon lean, so midday light isn't straight down
	private static final float SOUTH_TILT = 0.35f;
	private static final float MOON_STRENGTH = 0.35f;
	private static final float DIFFUSE_SCALE = 0.5f;

	/** Unit vector pointing from the ground towards the active light (sun or moon). */
	public final float[] lightDirection = new float[3];
	/** Directional light color, already scaled by intensity and lighting strength. */
	public final float[] lightColor = new float[3];
	public final float[] ambientColor = new float[3];
	public final float[] skyColor = new float[3];

	// scratch space, so updating every frame doesn't allocate
	private final float[] color = new float[3];
	private final float[] dayAmbient = new float[3];
	private final float[] daySky = new float[3];
	private final float[] duskSky = new float[3];

	/**
	 * Sun angle for the cycle mode, using wall-clock time so the cycle keeps going between sessions.
	 */
	public static double cycleAngle(long nowMillis, int dayMinutes, int nightMinutes)
	{
		long dayMs = Math.max(1, dayMinutes) * 60_000L;
		long nightMs = Math.max(1, nightMinutes) * 60_000L;
		long t = Math.floorMod(nowMillis, dayMs + nightMs);
		if (t < dayMs)
		{
			return Math.PI * t / dayMs;
		}
		return Math.PI + Math.PI * (t - dayMs) / nightMs;
	}

	/**
	 * Sun angle for a fixed hour of a 24 hour clock, with sunrise at 6:00 and sunset at 18:00.
	 */
	public static double staticAngle(int hour)
	{
		double a = (hour - 6) / 12.0 * Math.PI;
		return ((a % (2 * Math.PI)) + 2 * Math.PI) % (2 * Math.PI);
	}

	/**
	 * @param sunAngle        angle from {@link #cycleAngle} or {@link #staticAngle}
	 * @param strength        directional lighting strength, 0-1
	 * @param nightBrightness how bright night is, 0-1, where 0.4 is the default
	 * @param baseSky         the client's own sky color as rgb 0-1
	 */
	public void update(double sunAngle, float strength, float nightBrightness, float[] baseSky)
	{
		float sunX = (float) Math.cos(sunAngle);
		float sunUp = (float) Math.sin(sunAngle);

		float day = smoothstep(-0.2f, 0.2f, sunUp);
		float warm = smoothstep(-0.25f, 0.0f, sunUp) * (1f - smoothstep(0.1f, 0.6f, sunUp));
		float sunIntensity = smoothstep(-0.05f, 0.3f, sunUp);
		float moonIntensity = smoothstep(-0.05f, 0.3f, -sunUp) * MOON_STRENGTH;

		float intensity;
		float sign;
		if (sunIntensity >= moonIntensity)
		{
			mix(NOON_LIGHT, GOLDEN_LIGHT, warm, color);
			intensity = sunIntensity;
			sign = 1f;
		}
		else
		{
			System.arraycopy(MOON_LIGHT, 0, color, 0, 3);
			intensity = moonIntensity;
			// the moon sits opposite the sun
			sign = -1f;
		}

		float dx = sign * sunX;
		float dy = -sign * sunUp;
		float dz = -SOUTH_TILT;
		float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		lightDirection[0] = dx / len;
		lightDirection[1] = dy / len;
		lightDirection[2] = dz / len;

		float scale = intensity * strength * DIFFUSE_SCALE;
		for (int i = 0; i < 3; i++)
		{
			lightColor[i] = color[i] * scale;
		}

		// Ambient drops a little as the directional light gets stronger, so midday averages out
		// close to the stock look instead of getting brighter overall.
		mix(DAY_AMBIENT, DUSK_AMBIENT, warm, dayAmbient);
		float dayAmbientScale = 1.2f - 0.25f * strength;
		float nightScale = nightBrightness / 0.4f;
		for (int i = 0; i < 3; i++)
		{
			float d = Math.min(1f, dayAmbient[i] * dayAmbientScale);
			float n = Math.min(1f, NIGHT_AMBIENT[i] * nightScale);
			ambientColor[i] = n + (d - n) * day;
		}

		mix(baseSky, DUSK_SKY, 0.6f, duskSky);
		mix(baseSky, duskSky, warm, daySky);
		for (int i = 0; i < 3; i++)
		{
			float n = Math.min(1f, NIGHT_SKY[i] * nightScale);
			skyColor[i] = n + (daySky[i] - n) * day;
		}
	}

	/**
	 * Lighting that leaves the scene exactly as the stock renderer draws it.
	 */
	public void neutral(float[] baseSky)
	{
		lightDirection[0] = 0f;
		lightDirection[1] = -1f;
		lightDirection[2] = 0f;
		for (int i = 0; i < 3; i++)
		{
			lightColor[i] = 0f;
			ambientColor[i] = 1f;
			skyColor[i] = baseSky[i];
		}
	}

	private static float smoothstep(float edge0, float edge1, float x)
	{
		float t = Math.max(0f, Math.min(1f, (x - edge0) / (edge1 - edge0)));
		return t * t * (3f - 2f * t);
	}

	private static void mix(float[] a, float[] b, float t, float[] out)
	{
		for (int i = 0; i < 3; i++)
		{
			out[i] = a[i] + (b[i] - a[i]) * t;
		}
	}
}
