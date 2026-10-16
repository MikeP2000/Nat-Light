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

import lombok.AllArgsConstructor;

/**
 * The kinds of things that give off light, and how each one glows.
 * Radius is in tiles, height is how far above the object's base the light sits, in local units
 * (128 per tile), and flicker is how much the brightness wobbles, 0-1.
 */
@AllArgsConstructor
public enum LightType
{
	TORCH(1.00f, 0.55f, 0.20f, 5f, 1.0f, 110, 0.12f),
	FIRE(1.00f, 0.50f, 0.15f, 6f, 1.2f, 40, 0.15f),
	LAMP(1.00f, 0.78f, 0.45f, 5f, 0.7f, 140, 0.03f),
	CANDLE(1.00f, 0.70f, 0.35f, 3f, 0.45f, 80, 0.08f),
	FURNACE(1.00f, 0.45f, 0.15f, 4f, 0.9f, 60, 0.06f),
	LAVA(1.00f, 0.30f, 0.05f, 5f, 1.0f, 0, 0.05f),
	FIRE_SPELL(1.00f, 0.45f, 0.10f, 4f, 1.2f, 0, 0.20f),
	// a lantern hanging over a market stall
	STALL(1.00f, 0.75f, 0.40f, 4f, 0.8f, 200, 0.04f),
	// a lamp over a bank or exchange booth
	BOOTH(1.00f, 0.80f, 0.50f, 4f, 0.6f, 200, 0.02f),
	EXCHANGE_BOOTH(1.00f, 0.80f, 0.50f, 5f, 0.7f, 220, 0.02f),
	// a street lamp around the Grand Exchange
	PLAZA(1.00f, 0.80f, 0.50f, 7f, 0.8f, 240, 0.02f),
	// a soft ceiling light for buildings that have people in them but no light source of their own
	INTERIOR(1.00f, 0.80f, 0.55f, 6f, 0.7f, 260, 0.02f);

	final float r;
	final float g;
	final float b;
	final float radius;
	final float strength;
	final int height;
	final float flicker;
}
