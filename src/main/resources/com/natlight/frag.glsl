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

// moving lights (the player's spells), which every pixel checks
#define MAX_POINT_LIGHTS 8
uniform int pointLightCount;
uniform vec4 pointLightPos[MAX_POINT_LIGHTS];    // xyz position, w radius
uniform vec4 pointLightColor[MAX_POINT_LIGHTS];  // rgb color times brightness
uniform int pointLightBuilding[MAX_POINT_LIGHTS];  // building the light is confined to, -1 outdoors, 0 for none
// lights that stay put, one per column: row 0 = xyz position and radius, row 1 = rgb color times brightness and
// the building it's confined to (as pointLightBuilding)
uniform sampler2D lightData;
// per tile, the lights (index + 1, 0 for none) from lightData that reach it, in GRID_TEXELS RGBA texels
#define GRID_SLOTS 16
#define GRID_TEXELS 4
uniform usampler2D lightGrid;
// the lights of a room (flagged by INTERIOR_FLAG on their building) add up to at most this, so rooms with several
// lights aren't brighter than rooms with one
#define INTERIOR_FLAG 8192
// marks ceiling lights, for the debug view
#define CEILING_FLAG 16384
uniform float interiorLimit;
// per tile of the player's plane: r = building id, g = building whose light spills in through a window or
// doorway (low 14 bits) and how strongly (top 2 bits, 1-2), b = why a roofed tile isn't a building (debug view)
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

#define LIGHT_FLOOR_BELOW 270.0
// how far above its floor a light reaches before fading out under the ceiling (floors are 240 apart)
#define LIGHT_CEILING 205.0

// The surface being lit, shared by the per-light function below
vec3 sNormal;
float sFloorness;
vec2 sWallPos;
ivec2 sWallTile;
vec2 sHere;
ivec2 sHereTile;
ivec2 sFloorTile;
vec2 sFloorFrac;
uvec2 sWallV;
uvec2 sHereV;
uvec2 sFloorV00;
uvec2 sFloorV10;
uvec2 sFloorV01;
uvec2 sFloorV11;

// One point light's contribution to the surface
vec3 pointLight(vec4 posRadius, vec3 lightColor, int building, float floorY) {
  float share = 1.0;
  if (building != 0) {
    float wallShare = lightShare(sWallV, sWallTile, sWallPos, building);
    float floorShare = mix(
      mix(lightShare(sFloorV00, sFloorTile, sHere, building), lightShare(sFloorV10, sFloorTile + ivec2(1, 0), sHere, building), sFloorFrac.x),
      mix(lightShare(sFloorV01, sFloorTile + ivec2(0, 1), sHere, building), lightShare(sFloorV11, sFloorTile + ivec2(1, 1), sHere, building), sFloorFrac.x),
      sFloorFrac.y);
    share = mix(wallShare, floorShare, sFloorness);
    if (share <= 0.0) {
      return vec3(0.0);
    }
  }
  vec3 toLight = posRadius.xyz - fWorldPos;
  float dist = length(toLight);
  float radius = posRadius.w;
  if (dist >= radius) {
    return vec3(0.0);
  }
  float falloff = 1.0 - (dist * dist) / (radius * radius);
  falloff *= falloff;
  // a light only lights its own floor: not the floor below it or the ground outside far beneath it, and nothing
  // past its own ceiling, so a torch high on a wall doesn't light the foot of the walls on the floor above (y is down)
  float below = fWorldPos.y - posRadius.y;
  float aboveCeiling = (floorY - LIGHT_CEILING) - fWorldPos.y;
  falloff *= (1.0 - smoothstep(LIGHT_FLOOR_BELOW, LIGHT_FLOOR_BELOW + 70.0, below)) * (1.0 - smoothstep(0.0, 30.0, aboveCeiling));
  // wrap the lighting a little so floors right next to a light still catch some of it. Walls get none, so the
  // back of a wall stays dark.
  float facing = max(dot(sNormal, toLight / max(dist, 1.0)), 0.0);
  facing = mix(facing, facing * 0.75 + 0.25, sFloorness);
  return lightColor * falloff * facing * share;
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
  sNormal = normal;
  sFloorness = smoothstep(0.5, 0.9, abs(normal.y));
  sWallPos = (fWorldPos.xz + normal.xz * 24.0) / 128.0;
  sWallTile = ivec2(floor(sWallPos));
  sHere = fWorldPos.xz / 128.0;
  sHereTile = ivec2(floor(sHere));
  vec2 floorPos = sHere - 0.5;
  sFloorTile = ivec2(floor(floorPos));
  sFloorFrac = fract(floorPos);
  sWallV = buildingTile(sWallTile, mapSize);
  sHereV = buildingTile(sHereTile, mapSize);
  sFloorV00 = buildingTile(sFloorTile, mapSize);
  sFloorV10 = buildingTile(sFloorTile + ivec2(1, 0), mapSize);
  sFloorV01 = buildingTile(sFloorTile + ivec2(0, 1), mapSize);
  sFloorV11 = buildingTile(sFloorTile + ivec2(1, 1), mapSize);

  // lights that stay put: only the few the light grid lists for this tile. The grid stops light at walls, so walls
  // use the tile their face looks onto.
  ivec2 gridTile = sFloorness > 0.5 ? sHereTile : sWallTile;
  ivec2 gridSize = textureSize(lightGrid, 0);
  vec3 interior = vec3(0.0);
  if (all(greaterThanEqual(gridTile, ivec2(0))) && (gridTile.x + 1) * GRID_TEXELS <= gridSize.x && gridTile.y < gridSize.y) {
    uvec4 slots = uvec4(0u);
    for (int k = 0; k < GRID_SLOTS; k++) {
      if ((k & 3) == 0) {
        slots = texelFetch(lightGrid, ivec2(gridTile.x * GRID_TEXELS + (k >> 2), gridTile.y), 0);
      }
      uint index = slots[k & 3];
      if (index == 0u) {
        break;
      }
      int i = int(index) - 1;
      vec4 posRadius = texelFetch(lightData, ivec2(i, 0), 0);
      vec4 colorBuilding = texelFetch(lightData, ivec2(i, 1), 0);
      float floorY = texelFetch(lightData, ivec2(i, 2), 0).x;
      int code = int(colorBuilding.a);
      if (code >= CEILING_FLAG) {
        code -= CEILING_FLAG;
      }
      if (code >= INTERIOR_FLAG) {
        interior += pointLight(posRadius, colorBuilding.rgb, code - INTERIOR_FLAG, floorY);
      } else {
        light += pointLight(posRadius, colorBuilding.rgb, code, floorY);
      }
    }
  }
  // ease the room's lights towards the limit, so they light it evenly without stacking up or flattening out
  float interiorPeak = max(interior.r, max(interior.g, interior.b));
  if (interiorPeak > 0.0 && interiorLimit > 0.0) {
    interior *= interiorLimit * (1.0 - exp(-interiorPeak / interiorLimit)) / interiorPeak;
  }
  light += interior;

  // moving lights, which light up to a little above themselves
  for (int i = 0; i < pointLightCount; i++) {
    light += pointLight(pointLightPos[i], pointLightColor[i].rgb, pointLightBuilding[i], pointLightPos[i].y + LIGHT_CEILING - 40.0);
  }

  // ease off above full brightness instead of clipping, so spots right next to a torch don't blow out
  vec3 over = max(light - 1.0, 0.0);
  return color * (min(light, vec3(1.0)) + over / (1.0 + over));
}

// Debug view of the building map: lit buildings green, buildings with no NPC red, light spilling out of windows and
// doorways blue, roofed areas too small (grey) or too open (orange) to be a building, and a dot under each light
// (yellow for ceiling lights, white for the rest).
vec3 buildingDebug(vec3 color) {
  // dots only go on floors and table tops, not up the walls next to a light
  vec3 faceNormal = normalize(cross(dFdx(fWorldPos), dFdy(fWorldPos)));
  bool onFloor = abs(faceNormal.y) > 0.7;
  ivec2 mapSize = textureSize(buildingMap, 0);
  vec2 here = fWorldPos.xz / 128.0;
  ivec2 tile = ivec2(floor(here));
  if (any(lessThan(tile, ivec2(0))) || any(greaterThanEqual(tile, mapSize))) {
    return color;
  }
  uvec4 v = texelFetch(buildingMap, tile, 0);
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
  } else if (building == 0u && v.b != 0u) {
    // roofed but not a building: grey if too small, orange if too open
    tint = v.b == 2u ? vec3(0.6) : vec3(1.0, 0.55, 0.0);
    amount = 0.45;
  }
  ivec2 gridSize = textureSize(lightGrid, 0);
  if ((tile.x + 1) * GRID_TEXELS <= gridSize.x && tile.y < gridSize.y) {
    for (int k = 0; k < GRID_SLOTS; k++) {
      uint index = texelFetch(lightGrid, ivec2(tile.x * GRID_TEXELS + (k >> 2), tile.y), 0)[k & 3];
      if (index == 0u) {
        break;
      }
      vec4 posRadius = texelFetch(lightData, ivec2(int(index) - 1, 0), 0);
      if (onFloor && distance(posRadius.xz, fWorldPos.xz) < 20.0 && abs(fWorldPos.y - posRadius.y) < LIGHT_FLOOR_BELOW) {
        // ceiling lights yellow, everything else white
        return int(texelFetch(lightData, ivec2(int(index) - 1, 1), 0).a) >= CEILING_FLAG ? vec3(1.0, 0.85, 0.0) : vec3(1.0);
      }
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
