$input v_sinangle, v_cosangle, v_stretch, v_one, v_texCoord

/*  CRT shader
 *
 *  Copyright (C) 2010-2016 cgwg, Themaister and DOLLS
 *
 *  This program is free software; you can redistribute it and/or modify it
 *  under the terms of the GNU General Public License as published by the Free
 *  Software Foundation; either version 2 of the License, or (at your option)
 *  any later version.
 */

#include "common.sh"

// Comment the next line to disable interpolation in linear gamma (and gain speed).
//#define LINEAR_PROCESSING

// Enable 3x oversampling of the beam profile
#define OVERSAMPLE

// Use the older, purely gaussian beam profile
#define USEGAUSSIAN

// Macros.
#define FIX(c) max(abs(c), 1e-5)
#define PI 3.141592653589

SAMPLER2D(mpass_texture, 0);
SAMPLER2D(mask_texture, 1);
SAMPLER2D(blur_texture, 2);
SAMPLER2D(mipmap_texture, 3);

vec4 TEX2D(vec2 c)
{
  vec2 underscan = step(0.0,c) * step(0.0,vec2_splat(1.0)-c);
  vec4 col = texture2D(mpass_texture, c) * vec4_splat(underscan.x*underscan.y);
#ifdef LINEAR_PROCESSING
  col = pow(col, vec4_splat(CRTgamma.x));
#endif
  return col;
}

// Enable screen curvature.
uniform vec4 curvature;

uniform vec4 u_tex_size0;
uniform vec4 u_tex_size1;
uniform vec4 u_quad_dims;

uniform vec4 spot_size;
uniform vec4 spot_growth;
uniform vec4 spot_growth_power;

uniform vec4 u_interp;

uniform vec4 aperture_strength;
uniform vec4 aperture_brightboost;

uniform vec4 CRTgamma;
uniform vec4 monitorgamma;

uniform vec4 overscan;
uniform vec4 aspect;

uniform vec4 d;
uniform vec4 R;

uniform vec4 cornersize;
uniform vec4 cornersmooth;

uniform vec4 halation;
uniform vec4 rasterbloom;

uniform vec4 blurwidth;

vec3 texblur(vec2 c)
{
  vec3 col = pow(texture2D(blur_texture,c).rgb, vec3_splat(CRTgamma.x));
  // taper the blur texture outside its border with a gaussian
  float w = blurwidth.x / 320.0;
  c = min(c, vec2_splat(1.0)-c) * aspect.xy * vec2_splat(1.0/w);
  vec2 e2c = exp(-c*c);
  // approximation of erf gives smooth step
  // (convolution of gaussian with step)
  c = (step(0.0,c)-vec2_splat(0.5)) * sqrt(vec2_splat(1.0)-e2c) * (vec2_splat(1.0) + vec2_splat(0.1749)*e2c) + vec2_splat(0.5);
  return col * vec3_splat( c.x * c.y );
}

float intersect(vec2 xy , vec2 sinangle, vec2 cosangle)
{
  float A = dot(xy,xy)+d.x*d.x;
  float B = 2.0*(R.x*(dot(xy,sinangle)-d.x*cosangle.x*cosangle.y)-d.x*d.x);
  float C = d.x*d.x + 2.0*R.x*d.x*cosangle.x*cosangle.y;
  return (-B-sqrt(B*B-4.0*A*C))/(2.0*A);
}

vec2 bkwtrans(vec2 xy, vec2 sinangle, vec2 cosangle)
{
  float c = intersect(xy, sinangle, cosangle);
  vec2 pt = vec2_splat(c)*xy;
  pt -= vec2_splat(-R.x)*sinangle;
  pt /= vec2_splat(R.x);
  vec2 tang = sinangle/cosangle;
  vec2 poc = pt/cosangle;
  float A = dot(tang,tang)+1.0;
  float B = -2.0*dot(poc,tang);
  float C = dot(poc,poc)-1.0;
  float a = (-B+sqrt(B*B-4.0*A*C))/(2.0*A);
  vec2 uv = (pt-a*sinangle)/cosangle;
  float r = FIX(R.x*acos(a));
  return uv*r/sin(r/R.x);
}

vec2 transform(vec2 coord, vec3 stretch, vec2 sinangle, vec2 cosangle)
{
  coord = (coord-vec2_splat(0.5))*aspect.xy*stretch.z+stretch.xy;
  return (bkwtrans(coord, sinangle, cosangle)/overscan.xy/aspect.xy+vec2_splat(0.5));
}

float corner(vec2 coord)
{
  coord = (coord - vec2_splat(0.5)) * overscan.xy + vec2_splat(0.5);
  coord = min(coord, vec2_splat(1.0)-coord) * aspect.xy;
  vec2 cdist = vec2_splat(cornersize.x);
  coord = (cdist - min(coord,cdist));
  float dist = sqrt(dot(coord,coord));
  return clamp((max(cdist.x,1e-3)-dist)*cornersmooth.x,0.0, 1.0);
}

// Calculate the influence of a scanline on the current pixel.
//
// 'distance' is the distance in texture coordinates from the current
// pixel to the scanline in question.
// 'color' is the colour of the scanline at the horizontal location of
// the current pixel.
vec4 scanlineWeights(float distance, vec4 color)
{
  // "wid" controls the width of the scanline beam, for each RGB channel
  // The "weights" lines basically specify the formula that gives
  // you the profile of the beam, i.e. the intensity as
  // a function of distance from the vertical center of the
  // scanline. In this case, it is gaussian if width=2, and
  // becomes nongaussian for larger widths. Ideally this should
  // be normalized so that the integral across the beam is
  // independent of its width. That is, for a narrower beam
  // "weights" should have a higher peak at the center of the
  // scanline than for a wider beam.
#ifdef USEGAUSSIAN
  vec4 wid = spot_size.x + spot_growth.x * pow(color, vec4_splat(spot_growth_power.x));
  vec4 weights = vec4(distance / wid);
  float maxwid = spot_size.x + spot_growth.x;
  float norm = maxwid / ( 1.0 + 2.0 * exp(-1.0/(maxwid*maxwid)) );
  return norm * exp(-weights * weights) / wid;
#else
  vec4 wid = 2.0 + 2.0 * pow(color, vec4_splat(4.0));
  vec4 weights = vec4_splat(abs(distance) / 0.3);
  return 1.4 * exp(-pow(weights * inversesqrt(0.5 * wid), wid)) / (0.6 + 0.2 * wid);
#endif
}

vec4 cubic(vec4 x, float B, float C)
{
  // https://en.wikipedia.org/wiki/Mitchell%E2%80%93Netravali_filters
  vec2 a = x.yz; // components in [0,1]
  vec2 b = x.xw; // components in [1,2]
  vec2 a2 = a*a;
  vec2 b2 = b*b;
  a = (2.0-1.5*B-1.0*C)*a*a2 + (-3.0+2.0*B+C)*a2 + (1.0-(1.0/3.0)*B);
  b = ((-1.0/6.0)*B-C)*b*b2 + (B+5.0*C)*b2 + (-2.0*B-8.0*C)*b + ((4.0/3.0)*B+4.0*C);
  return vec4(b.x,a.x,a.y,b.y);
}

vec4 x_coeffs(vec4 x, float pos_x)
{
  if (u_interp.x < 0.5) { // box
    float wid = length(vec2(dFdx(pos_x),dFdy(pos_x)));
    float dx = clamp((0.5 + 0.5*wid - x.y)/wid, 0.0, 1.0);
    return vec4(0.0,dx,1.0-dx,0.0);
  } else if (u_interp.x < 1.5) { // linear
    return vec4(0.0, 1.0-x.y, 1.0-x.z, 0.0);
  } else if (u_interp.x < 2.5) { // Lanczos
    // Prevent division by zero.
    vec4 coeffs = FIX(PI * x);
    // Lanczos2 kernel.
    coeffs = 2.0 * sin(coeffs) * sin(coeffs / 2.0) / (coeffs * coeffs);
    // Normalize.
    coeffs /= dot(coeffs, vec4_splat(1.0));
    return coeffs;
  } else if (u_interp.x < 3.5) { // Catmull-Rom
    return cubic(x,0.0,0.5);
  } else if (u_interp.x < 4.5) { // Mitchell-Netravali
    return cubic(x,1.0/3.0,1.0/3.0);
  } else /*if (u_interp.x < 5.5)*/ { // B-spline
    return cubic(x,1.0,0.0);
  }
}

vec4 sample_scanline(vec2 xy, vec4 coeffs, float onex)
{
  // Calculate the effective colour of the given
  // scanline at the horizontal location of the current pixel,
  // using the Lanczos coefficients.
  vec4 col = clamp(TEX2D(xy + vec2(-onex, 0.0))*coeffs.x +
                   TEX2D(xy)*coeffs.y +
                   TEX2D(xy +vec2(onex, 0.0))*coeffs.z +
                   TEX2D(xy + vec2(2.0 * onex, 0.0))*coeffs.w , 0.0, 1.0);
  return col;
}

void main()
{
  // Here's a helpful diagram to keep in mind while trying to
  // understand the code:
  //
  //  |      |      |      |      |
  // -------------------------------
  //  |      |      |      |      |
  //  |  00  |  10  |  20  |  30  | <-- previous scanline
  //  |      |      |      |      |
  // -------------------------------
  //  |      |      |      |      |
  //  |  01  |  11  |  21  |  31  | <-- current scanline
  //  |      | @    |      |      |
  // -------------------------------
  //  |      |      |      |      |
  //  |  02  |  12  |  22  |  32  | <-- next scanline
  //  |      |      |      |      |
  // -------------------------------
  //  |      |      |      |      |
  //
  // Each character-cell represents a pixel on the output
  // surface, "@" represents the current pixel (always somewhere
  // in the current scan-line). The grid of lines represents the
  // edges of the texels of the underlying texture.
  // The "deluxe" shader includes contributions from the
  // previous, current, and next scanlines.

  // Texture coordinates of the texel containing the active pixel.
  vec2 xy;
  if (curvature.x > 0.5)
    xy = transform(v_texCoord, v_stretch, v_sinangle, v_cosangle);
  else
    xy = (v_texCoord-vec2_splat(0.5))/overscan.xy+vec2_splat(0.5);
  float cval = corner(xy);
  // extract average brightness from the mipmap texture
  float avgbright = dot(texture2D(mipmap_texture,vec2(1.0,1.0)).rgb,vec3_splat(1.0))/3.0;
  float rbloom = 1.0 - rasterbloom.x * ( avgbright - 0.5 );
  // expand the screen when average brightness is higher
  xy = (xy - vec2_splat(0.5)) * rbloom + vec2_splat(0.5);
  vec2 xy0 = xy;

  // Of all the pixels that are mapped onto the texel we are
  // currently rendering, which pixel are we currently rendering?
  vec2 ratio_scale = xy * u_tex_size0.xy - vec2(0.5,0.0);

#ifdef OVERSAMPLE
  float filter = fwidth(ratio_scale.y);
#endif
  vec2 uv_ratio = fract(ratio_scale) - vec2(0.0,0.5);

  // Snap to the center of the underlying texel.
  xy = (floor(ratio_scale) + vec2_splat(0.5)) / u_tex_size0.xy;

  // Calculate scaling coefficients describing the effect
  // of various neighbour texels in a scanline on the current
  // pixel.
  vec4 coeffs = x_coeffs(vec4(1.0 + uv_ratio.x, uv_ratio.x, 1.0 - uv_ratio.x, 2.0 - uv_ratio.x), ratio_scale.x);

  vec4 col = sample_scanline(xy, coeffs, v_one.x);
  vec4 col_prev = sample_scanline(xy + vec2(0.0,-v_one.y), coeffs, v_one.x);
  vec4 col_next = sample_scanline(xy + vec2(0.0, v_one.y), coeffs, v_one.x);

#ifndef LINEAR_PROCESSING
  col  = pow(col , vec4_splat(CRTgamma.x));
  col_prev = pow(col_prev, vec4_splat(CRTgamma.x));
  col_next = pow(col_next, vec4_splat(CRTgamma.x));
#endif

  // Calculate the influence of the current and next scanlines on
  // the current pixel.
  vec4 weights  = scanlineWeights(uv_ratio.y, col);
  vec4 weights_prev = scanlineWeights(uv_ratio.y + 1.0, col_prev);
  vec4 weights_next = scanlineWeights(uv_ratio.y - 1.0, col_next);
#ifdef OVERSAMPLE
  uv_ratio.y =uv_ratio.y+1.0/3.0*filter;
  weights = (weights+scanlineWeights(uv_ratio.y, col))/3.0;
  weights_prev=(weights_prev+scanlineWeights(uv_ratio.y+1.0, col_prev))/3.0;
  weights_next=(weights_next+scanlineWeights(uv_ratio.y-1.0, col_next))/3.0;
  uv_ratio.y =uv_ratio.y-2.0/3.0*filter;
  weights=weights+scanlineWeights(uv_ratio.y, col)/3.0;
  weights_prev=weights_prev+scanlineWeights(uv_ratio.y+1.0, col_prev)/3.0;
  weights_next=weights_next+scanlineWeights(uv_ratio.y-1.0, col_next)/3.0;
#endif
  vec3 mul_res  = (col * weights + col_prev * weights_prev + col_next * weights_next).rgb;

  // halation and corners
  vec3 blur = texblur(xy0);
  // include factor of rbloom:
  // (probably imperceptible) brightness reduction when raster grows
  mul_res = mix(mul_res, blur, halation.x) * vec3_splat(cval*rbloom);

  // Shadow mask
  xy = v_texCoord.xy * u_quad_dims.xy / u_tex_size1.xy;
  vec4 mask = texture2D(mask_texture, xy);
  // count of total bright pixels is encoded in the mask's alpha channel
  float nbright = 255.0 - 255.0*mask.a;
  // fraction of bright pixels in the mask
  float fbright = nbright / ( u_tex_size1.x * u_tex_size1.y );
  // average darkening factor of the mask
  float aperture_average = mix(1.0-aperture_strength.x*(1.0-aperture_brightboost.x), 1.0, fbright);
  // colour of dark mask pixels
  vec3 clow = vec3_splat(1.0-aperture_strength.x) * mul_res + vec3_splat(aperture_strength.x*(aperture_brightboost.x)) * mul_res * mul_res;
  float ifbright = 1.0 / fbright;
  // colour of bright mask pixels
  vec3 chi = vec3_splat(ifbright*aperture_average) * mul_res - vec3_splat(ifbright - 1.0) * clow;
  vec3 cout = mix(clow,chi,mask.rgb); // mask texture selects dark vs bright

  // Convert the image gamma for display on our output device.
  cout = pow(cout, vec3_splat(1.0 / monitorgamma.x));

  gl_FragColor = vec4(cout,1.0);
}
