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
#version 330

//#define FRAG_UVS
//#define ZBUF_DEBUG

#include colorblind_mode

uniform sampler2DArray textures;
uniform float brightness;
uniform float smoothBanding;
uniform vec4 fogColor;
uniform float textureLightMode;
uniform vec3 lightDirection;
uniform vec3 lightColor;
uniform vec3 ambientColor;
uniform int useLighting;

#define MAX_POINT_LIGHTS 32
uniform int pointLightCount;
uniform vec4 pointLightPos[MAX_POINT_LIGHTS];    // xyz position, w radius
uniform vec4 pointLightColor[MAX_POINT_LIGHTS];  // rgb color times brightness
uniform int pointLightBuilding[MAX_POINT_LIGHTS];  // building the light is confined to, 0 for none
// per tile of the player's plane: r = building id, g = building whose light spills in through a window or
// doorway (low 14 bits) and how strongly (top 2 bits, 1-2)
uniform usampler2D buildingMap;
uniform int debugBuildings;

layout(std140) uniform uniforms {
  float cameraYaw;
  float cameraPitch;
  float cameraX;
  float cameraY;
  float cameraZ;
};

in vec4 fColor;
noperspective centroid in float fHsl;
flat in int fTextureId;
in vec2 fUv;
in float fFogAmount;
in vec3 fWorldPos;
#ifdef ZBUF_DEBUG
in float fDepth;
#endif

out vec4 FragColor;

#include "hsl_to_rgb.glsl"

#if COLORBLIND_MODE > 0
#include "colorblind.glsl"
#endif

// The building map entry for a tile, or nothing for tiles off the map.
uvec2 buildingTile(ivec2 tile, ivec2 mapSize) {
  if (any(lessThan(tile, ivec2(0))) || any(greaterThanEqual(tile, mapSize))) {
    return uvec2(0u);
  }
  return texelFetch(buildingMap, tile, 0).rg;
}

// How much of a tile's building is at a point: all of it, or only the inside half of a tile cut by an angled wall.
float cornerShare(uint v, ivec2 tile, vec2 pos) {
  uint corner = v >> 13;
  if (corner == 0u) {
    return 1.0;
  }
  vec2 f = clamp(pos - vec2(tile), 0.0, 1.0);
  // positive on the inside half
  float side = corner == 1u ? f.x + f.y - 1.0  // north-east
             : corner == 2u ? f.y - f.x        // north-west
             : corner == 3u ? f.x - f.y        // south-east
             : 1.0 - f.x - f.y;                // south-west
  return smoothstep(-0.05, 0.05, side);
}

// How much of a light reaches a point on a tile (v is the tile's building map entry). Lights in a building get all
// of it inside the building and some where it spills out of windows and doorways. Lights outdoors (building -1) get
// none of it inside any building.
float lightShare(uvec2 v, ivec2 tile, vec2 pos, int building) {
  uint id = v.r & 0x0FFFu;
  if (building < 0) {
    return id == 0u ? 1.0 : 1.0 - cornerShare(v.r, tile, pos);
  }
  if (id == uint(building)) {
    return cornerShare(v.r, tile, pos);
  }
  if ((v.g & 0x3FFFu) == uint(building)) {
    return float(v.g >> 14) * 0.5;
  }
  return 0.0;
}

// Shades the game's own (already lit) colors with the sun or moon and the ambient light.
// Vertices carry no normals, so the face normal comes from the screen space derivatives
// of the world position, flipped to face the camera.
vec3 applyLighting(vec3 color) {
  vec3 normal = normalize(cross(dFdx(fWorldPos), dFdy(fWorldPos)));
  vec3 toCamera = vec3(cameraX, cameraY, cameraZ) - fWorldPos;
  if (dot(normal, toCamera) < 0.0) {
    normal = -normal;
  }
  float diffuse = max(dot(normal, lightDirection), 0.0);
  vec3 light = ambientColor + lightColor * diffuse;

  // Which building a surface belongs to. Walls use the tile just along their normal, so a wall's outside face
  // counts as outside and blocks the light. Floors blend the four nearest tiles, so the light fades across tile
  // edges instead of stopping in hard squares.
  ivec2 mapSize = textureSize(buildingMap, 0);
  vec2 wallPos = (fWorldPos.xz + normal.xz * 24.0) / 128.0;
  ivec2 wallTile = ivec2(floor(wallPos));
  vec2 here = fWorldPos.xz / 128.0;
  ivec2 hereTile = ivec2(floor(here));
  vec2 floorPos = here - 0.5;
  ivec2 floorTile = ivec2(floor(floorPos));
  vec2 floorFrac = fract(floorPos);
  float floorness = smoothstep(0.5, 0.9, abs(normal.y));
  uvec2 wallV = buildingTile(wallTile, mapSize);
  uvec2 hereV = buildingTile(hereTile, mapSize);
  uvec2 floorV00 = buildingTile(floorTile, mapSize);
  uvec2 floorV10 = buildingTile(floorTile + ivec2(1, 0), mapSize);
  uvec2 floorV01 = buildingTile(floorTile + ivec2(0, 1), mapSize);
  uvec2 floorV11 = buildingTile(floorTile + ivec2(1, 1), mapSize);

  for (int i = 0; i < pointLightCount; i++) {
    int building = pointLightBuilding[i];
    bool confined = building != 0;
    float share = 1.0;
    if (confined) {
      float wallShare = lightShare(wallV, wallTile, wallPos, building);
      float floorShare = mix(
        mix(lightShare(floorV00, floorTile, here, building), lightShare(floorV10, floorTile + ivec2(1, 0), here, building), floorFrac.x),
        mix(lightShare(floorV01, floorTile + ivec2(0, 1), here, building), lightShare(floorV11, floorTile + ivec2(1, 1), here, building), floorFrac.x),
        floorFrac.y);
      // only blend on floors that get some of the light themselves, so it never creeps out past the walls
      float own = lightShare(hereV, hereTile, here, building);
      floorShare = own <= 0.0 ? 0.0 : min(floorShare, own);
      share = mix(wallShare, floorShare, floorness);
      if (share <= 0.0) {
        continue;
      }
    }
    vec3 toLight = pointLightPos[i].xyz - fWorldPos;
    float dist = length(toLight);
    float radius = pointLightPos[i].w;
    if (dist >= radius) {
      continue;
    }
    float falloff = 1.0 - (dist * dist) / (radius * radius);
    falloff *= falloff;
    // wrap the lighting a little so floors right next to a light still catch some of it. Walls get none, so the
    // back of a wall stays dark.
    float facing = max(dot(normal, toLight / max(dist, 1.0)), 0.0);
    facing = mix(facing, facing * 0.75 + 0.25, floorness);
    light += pointLightColor[i].rgb * falloff * facing * share;
  }

  return color * min(light, vec3(2.0));
}

// Debug view of the building map: lit buildings green, buildings with no NPC red, light spilling out of windows and
// doorways blue, and a white dot under each building light.
vec3 buildingDebug(vec3 color) {
  ivec2 mapSize = textureSize(buildingMap, 0);
  vec2 here = fWorldPos.xz / 128.0;
  ivec2 tile = ivec2(floor(here));
  if (any(lessThan(tile, ivec2(0))) || any(greaterThanEqual(tile, mapSize))) {
    return color;
  }
  uvec2 v = texelFetch(buildingMap, tile, 0).rg;
  uint building = v.r & 0x0FFFu;
  vec3 tint = vec3(0.0);
  float amount = 0.0;
  if (building != 0u) {
    float inside = cornerShare(v.r, tile, here);
    tint = (v.r & 0x1000u) != 0u ? vec3(0.1, 1.0, 0.2) : vec3(1.0, 0.15, 0.1);
    amount = 0.55 * inside;
    // outline the tiles so you can see where each one starts
    vec2 f = fract(here);
    if (min(min(f.x, f.y), min(1.0 - f.x, 1.0 - f.y)) < 0.03) {
      amount = max(amount, 0.3);
    }
  }
  if (amount < 0.3 && (v.g & 0x3FFFu) != 0u) {
    tint = vec3(0.2, 0.4, 1.0);
    amount = 0.25 * float(v.g >> 14);
  }
  for (int i = 0; i < pointLightCount; i++) {
    if (pointLightBuilding[i] != 0 && distance(pointLightPos[i].xz, fWorldPos.xz) < 20.0) {
      return vec3(1.0);
    }
  }
  return mix(color, tint, amount);
}

#ifdef ZBUF_DEBUG
float linear_depth(float depth) {
  // depth is computed as 100/z, solve for z
  float z = 100 / depth;
  return 1 - z / 10000;  // we don't have a far plane, but the client uses 10000
}
#endif

void main() {
  vec4 c;

  if (fTextureId > 0) {
    int textureIdx = fTextureId - 1;

    vec4 textureColor = texture(textures, vec3(fUv, float(textureIdx)));
    vec4 textureColor0 = textureLod(textures, vec3(fUv, float(textureIdx)), 0.f);

    if (textureColor0.a < 1.f)
      discard;

    textureColor = vec4(textureColor.rgb, 1.f);

    textureColor = pow(textureColor, vec4(brightness, brightness, brightness, 1.f));

    // textured triangles hsl is a 7 bit lightness 2-126
    float light = fHsl / 127.f;
    vec3 mul = (1.f - textureLightMode) * vec3(light) + textureLightMode * fColor.rgb;
    c = textureColor * vec4(mul, fColor.a);
  } else {
    // pick interpolated hsl or rgb depending on smooth banding setting
    vec3 hsl = vec3(int(fHsl) >> 10 & 63, int(fHsl) >> 7 & 7, int(fHsl) & 127);
    vec3 rgb = mix(fColor.rgb, hslToRgb(hsl), smoothBanding);
    c = vec4(rgb, fColor.a);
  }

  if (useLighting != 0) {
    c.rgb = applyLighting(c.rgb);
    if (debugBuildings != 0) {
      c.rgb = buildingDebug(c.rgb);
    }
  }

#if COLORBLIND_MODE > 0
  c.rgb = colorblind(c.rgb);
#endif

  vec3 mixedColor = mix(c.rgb, fogColor.rgb, fFogAmount);
  FragColor = vec4(mixedColor, c.a);

#ifdef FRAG_UVS
  if (fTextureId > 0) {
    FragColor = vec4(fUv.x, 0, fUv.y, 1);
  }
#endif

#ifdef ZBUF_DEBUG
  float dc = linear_depth(fDepth);
  if (dc > 1.0) {
    FragColor = vec4(1, 0, 0, 1);
  } else if (dc < -1.0) {
    FragColor = vec4(0, 0, 1, 1);
  } else if (dc < 0.0) {
    FragColor = vec4(0, 1, 0, 1);
  } else {
    FragColor = vec4(dc, dc, dc, 1);
  }
#endif
}
