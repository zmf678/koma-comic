// Liquid Glass Optical Shader for KomaComic (WPF DirectX ShaderEffect — Universal ps_2_0)
// Faithfully reproduces WWDC25 / shuding-style Liquid Glass in BOTH Light and Dark modes:
//   - Exact roundedRectSDF edge-only convex meniscus refraction (center stays undistorted)
//   - Visible R/G/B bevel chromatic dispersion along curved rim
//   - Optical clarity lift + ultra-translucent glass body
//   - Grazing dark glass-thickness outer lip (critical for crisp glass contrast on light backgrounds!)
//   - Concave inner depth shadow + gleaming top caustic sheen
//   - Razor-sharp dual-angle opposing Fresnel specular rim + pointer-reactive light angle

sampler2D implicitInput : register(s0);

// C0: ElementMetrics (x = width px, y = height px, z = cornerRadius px, w = 1.0 / bevelWidth)
float4 ElementMetrics : register(c0);
// C1: OpticalParams (x = refractionStrength px, y = chromatic px, z = contrastScale, w = biasAdd)
float4 OpticalParams : register(c1);
// C2: LightParams (x = lightDirX, y = lightDirY, z = rimIntensity, w = innerShadowOpacity)
float4 LightParams : register(c2);
// C3: TintColor (RGB = tint color, A = tint alpha)
float4 TintColor : register(c3);
// C4: SpecularColor (RGB premultiplied by alpha, A = sheenStrength)
float4 SpecularColor : register(c4);
// C5: SecondaryRimColor (RGB premultiplied by alpha, A = outerDarkLipOpacity)
float4 SecondaryRimColor : register(c5);
// C6: Precomp (x = 1.0 / width, y = 1.0 / height, z = halfW - radius, w = halfH - radius)
float4 Precomp : register(c6);

float4 main_ps2(float2 uv : TEXCOORD) : COLOR
{
    // Aspect-correct rounded-rectangle SDF
    float2 p = (uv - 0.5) * ElementMetrics.xy;
    float2 q = abs(p) - Precomp.zw;
    float sdf = min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - ElementMetrics.z;
    float shapeAlpha = saturate(0.5 - sdf);

    // Smooth analytic outward normal (corners + straight edges)
    float2 dir = max(q, 0.0) + ((q.x > q.y) ? float2(0.001, 0.0) : float2(0.0, 0.001));
    float2 normal = normalize(dir) * ((p >= 0.0) ? 1.0 : -1.0);

    // Edge-only optical meniscus: 0 in center, curves smoothly across outer bevel
    float edgeT = saturate(1.0 + sdf * ElementMetrics.w);
    float curve = edgeT * edgeT;
    float steep = curve * edgeT;

    // Inward optical refraction + R/G/B prismatic chromatic dispersion at curved rim
    float2 nInv = -normal * Precomp.xy;
    float2 refrUV = saturate(uv + nInv * (OpticalParams.x * steep));
    float2 chrOff = nInv * (OpticalParams.y * steep);

    float3 col;
    col.r = tex2D(implicitInput, saturate(refrUV + chrOff)).r;
    col.g = tex2D(implicitInput, refrUV).g;
    col.b = tex2D(implicitInput, saturate(refrUV - chrOff)).b;

    // Optical clarity lift + low-opacity glass tint (clearer along the refracted bevel)
    col = saturate(col * OpticalParams.z + OpticalParams.w);
    float3 glass = lerp(col, TintColor.rgb, TintColor.a * (1.0 - 0.45 * curve));

    // Shared edge band: outer grazing dark lip + concave bottom/bevel depth shadow
    float edgeBand = saturate(1.0 + sdf * 0.48);
    glass *= (1.0 - LightParams.w * (uv.y * 0.52 + curve * 0.52) - SecondaryRimColor.a * edgeBand);

    // Top caustic gloss reflection
    float sheen = saturate(1.0 - uv.y * 2.0);
    glass += SpecularColor.rgb * (sheen * sheen * SpecularColor.a);

    // Dual-angle opposing Fresnel specular rim (crisp outer arc + bevel glow)
    float rim = (edgeBand * 0.96 + steep * 0.38) * LightParams.z;
    float key = saturate(dot(normal, LightParams.xy) * 0.60 + 0.46);
    glass += lerp(SecondaryRimColor.rgb, SpecularColor.rgb, key * key) * rim;

    glass = saturate(glass);
    return float4(glass * shapeAlpha, shapeAlpha);
}
