/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
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
package com.natlight;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;
import static com.natlight.NatLightPlugin.MAX_DISTANCE;
import static com.natlight.NatLightPlugin.MAX_FOG_DEPTH;
import com.natlight.config.AntiAliasingMode;
import com.natlight.config.ColorBlindMode;
import com.natlight.config.TimeMode;
import com.natlight.config.UIScalingMode;

@ConfigGroup(NatLightConfig.GROUP)
public interface NatLightConfig extends Config
{
	String GROUP = "natlight";

	@ConfigSection(
		name = "Lighting",
		description = "Sun, moon and day/night cycle settings.",
		position = -10
	)
	String lightingSection = "lighting";

	@ConfigItem(
		keyName = "timeMode",
		name = "Time of day",
		description = "Run a day/night cycle, or keep the time fixed.",
		position = -9,
		section = lightingSection
	)
	default TimeMode timeMode()
	{
		return TimeMode.CYCLE;
	}

	@Range(
		min = 1,
		max = 240
	)
	@ConfigItem(
		keyName = "dayLength",
		name = "Day length (minutes)",
		description = "How long the day lasts, from sunrise to sunset, in real minutes.",
		position = -8,
		section = lightingSection
	)
	default int dayLength()
	{
		return 20;
	}

	@Range(
		min = 1,
		max = 240
	)
	@ConfigItem(
		keyName = "nightLength",
		name = "Night length (minutes)",
		description = "How long the night lasts, from sunset to sunrise, in real minutes.",
		position = -7,
		section = lightingSection
	)
	default int nightLength()
	{
		return 10;
	}

	@Range(
		max = 23
	)
	@ConfigItem(
		keyName = "staticHour",
		name = "Static time (hour)",
		description = "Hour of the day to use when Time of day is set to Static. Sunrise is 6, sunset is 18.",
		position = -6,
		section = lightingSection
	)
	default int staticHour()
	{
		return 12;
	}

	@Range(
		max = 100
	)
	@ConfigItem(
		keyName = "lightingStrength",
		name = "Sun strength (%)",
		description = "How strongly the sun and moon shade the scene. 0 keeps the stock shading.",
		position = -5,
		section = lightingSection
	)
	default int lightingStrength()
	{
		return 50;
	}

	@Range(
		min = 10,
		max = 100
	)
	@ConfigItem(
		keyName = "nightBrightness",
		name = "Night brightness (%)",
		description = "How bright the scene stays at night.",
		position = -4,
		section = lightingSection
	)
	default int nightBrightness()
	{
		return 40;
	}

	@ConfigItem(
		keyName = "lightSources",
		name = "Light sources",
		description = "Make torches, fires, lamps and your fire spells light up their surroundings.",
		position = -3,
		section = lightingSection
	)
	default boolean lightSources()
	{
		return true;
	}

	@Range(
		max = 200
	)
	@ConfigItem(
		keyName = "lightSourceBrightness",
		name = "Light source brightness (%)",
		description = "How bright torches, fires and other light sources are.",
		position = -2,
		section = lightingSection
	)
	default int lightSourceBrightness()
	{
		return 100;
	}

	@Range(
		max = MAX_DISTANCE
	)
	@ConfigItem(
		keyName = "drawDistance",
		name = "Draw distance",
		description = "Draw distance.",
		position = 1
	)
	default int drawDistance()
	{
		return 50;
	}

	@ConfigItem(
		keyName = "hideUnrelatedMaps",
		name = "Hide unrelated maps",
		description = "Hide unrelated map areas you shouldn't see.",
		position = 2
	)
	default boolean hideUnrelatedMaps()
	{
		return true;
	}

	@Range(
		max = 5
	)
	@ConfigItem(
		keyName = "expandedMapLoadingChunks",
		name = "Extended map loading",
		description = "Extra map area to load, in 8 tile chunks.",
		position = 1
	)
	default int expandedMapLoadingZones()
	{
		return 3;
	}

	@ConfigItem(
		keyName = "smoothBanding",
		name = "Remove color banding",
		description = "Smooths out the color banding that is present in the CPU renderer.",
		position = 2
	)
	default boolean smoothBanding()
	{
		return true;
	}

	@ConfigItem(
		keyName = "antiAliasingMode",
		name = "Anti aliasing",
		description = "Configures the anti-aliasing mode.",
		position = 3
	)
	default AntiAliasingMode antiAliasingMode()
	{
		return AntiAliasingMode.MSAA_2;
	}

	@ConfigItem(
		keyName = "uiScalingMode",
		name = "UI scaling mode",
		description = "Sampling function to use for the UI in stretched mode.",
		position = 4
	)
	default UIScalingMode uiScalingMode()
	{
		return UIScalingMode.HYBRID;
	}

	@Range(
		max = MAX_FOG_DEPTH
	)
	@ConfigItem(
		keyName = "fogDepth",
		name = "Fog depth",
		description = "Distance from the scene edge the fog starts.",
		position = 5
	)
	default int fogDepth()
	{
		return 0;
	}

	@Range(
		min = 0,
		max = 16
	)
	@ConfigItem(
		keyName = "anisotropicFilteringLevel",
		name = "Anisotropic filtering",
		description = "Configures the anisotropic filtering level.",
		position = 7
	)
	default int anisotropicFilteringLevel()
	{
		return 1;
	}

	@ConfigItem(
		keyName = "colorBlindMode",
		name = "Colorblindness correction",
		description = "Adjusts colors to account for colorblindness.",
		position = 8
	)
	default ColorBlindMode colorBlindMode()
	{
		return ColorBlindMode.NONE;
	}

	@Range(
		min = 0,
		max = 100
	)
	@ConfigItem(
		keyName = "colorBlindIntensity",
		name = "Colorblindness intensity",
		description = "Strength of the colorblindness correction effect.",
		position = 9
	)
	default int colorBlindIntensity()
	{
		return 100;
	}

	@ConfigItem(
		keyName = "brightTextures",
		name = "Bright textures",
		description = "Use old texture lighting method which results in brighter game textures.",
		position = 10
	)
	default boolean brightTextures()
	{
		return false;
	}

	@ConfigItem(
		keyName = "unlockFps",
		name = "Unlock FPS",
		description = "Removes the 50 FPS cap for camera movement.",
		position = 11
	)
	default boolean unlockFps()
	{
		return true;
	}

	enum SyncMode
	{
		OFF,
		ON,
		ADAPTIVE
	}

	@ConfigItem(
		keyName = "vsyncMode",
		name = "Vsync mode",
		description = "Method to synchronize frame rate with refresh rate.",
		position = 12
	)
	default SyncMode syncMode()
	{
		return SyncMode.OFF;
	}

	@ConfigItem(
		keyName = "fpsTarget",
		name = "FPS target",
		description = "Target FPS when 'Unlock FPS' is enabled and 'Vsync mode' is off.",
		position = 13
	)
	@Range(
		min = 1,
		max = 999
	)
	default int fpsTarget()
	{
		return 60;
	}

	@ConfigItem(
		keyName = "removeVertexSnapping",
		name = "Remove vertex snapping",
		description = "Removes vertex snapping from most animations.",
		position = 14
	)
	default boolean removeVertexSnapping()
	{
		return true;
	}

	@ConfigItem(
		keyName = "numThreads",
		name = "Threads",
		description = "Number of render threads to use.",
		position = 20
	)
	@Range(min = 0, max = 15)
	default int numThreads()
	{
		return 3;
	}
}
