#include <metal_stdlib>
using namespace metal;

// Front-facing implicit volume. Height gradients generate the optical normal;
// colour changes do not substitute for the depth and moving reflections.
static float jellyHeight(float2 uv, device const float *radii, int count,
                         device const float *events, int eventCount, float4 drag, float duration) {
    float2 p = uv - float2(.5, .52);
    float angle = atan2(p.y, p.x) / (2.0 * M_PI_F);
    float sample = fract(angle + 1.0) * float(count);
    int index = int(floor(sample));
    // Periodic Catmull-Rom interpolation preserves the tangent at every sample
    // and at the 0/2pi seam. Linear interpolation makes normals jump in spokes.
    float a = radii[(index + count - 1) % count];
    float b = radii[index % count];
    float c = radii[(index + 1) % count];
    float d = radii[(index + 2) % count];
    float t = fract(sample);
    float radius = .5 * ((2*b) + (-a+c)*t + (2*a-5*b+4*c-d)*t*t
                         + (-a+3*b-3*c+d)*t*t*t);
    // The body rounds inwards from its edge, then levels into a soft plateau.
    // A radial hemisphere makes lobes meet in bright spokes at the centre.
    float slope = .5 * ((-a+c) + 2*(2*a-5*b+4*c-d)*t
                         + 3*(-a+3*b-3*c+d)*t*t) * float(count)/(2*M_PI_F);
    float inset = max(0.0, radius-length(p)) / sqrt(1.0+pow(slope/max(radius,.08),2.0));
    float shoulder = .30 * sqrt(max(0.0,1.0-exp(-inset/.085)));
    // Fade polar geometry out at the centre: even a tiny angle-dependent
    // height there becomes a needle-like normal when the finger presses it.
    float height = mix(.30, shoulder, smoothstep(0.0,.16,length(p)));
    float deformation = 0.0;
    for (int i = 0; i + 8 < eventCount; i += 9) {
        float d = distance(uv, float2(events[i], events[i+1]));
        float age = events[i+2];
        float envelope = pow(max(0.0, 1.0 - age/duration), 2.0);
        float strength = events[i+3];
        float released = events[i+4];
        float bodyEnvelope = 1.0-smoothstep(0.0,.50,age);
        float depth = (cos(age*14.0)*released + sin(age*14.0)*strength) * bodyEnvelope;
        float dent = -.073 * depth * exp(-d*d/.075);
        // Mirrored broad crests have zero radial slope at the fingertip.
        // A travelling radial sine has a cusp there, which lights like a needle.
        float front = age*.60;
        float ring = .026 * strength * envelope * smoothstep(0.0,.08,age)
                   * (exp(-pow((d-front)/.24,2.0)) + exp(-pow((d+front)/.24,2.0)));
        deformation += dent + ring;
    }
    float dragDistance = distance(uv, drag.xy);
    deformation -= .073 * drag.z * exp(-dragDistance*dragDistance/.075);
    return max(0.0, height + .13*tanh(deformation/.13) * smoothstep(0.0,.15, height));
}

[[ stitchable ]] half4 jellySurface(float2 position, half4 source, float4 bounds,
        float3 base, device const float *radii, int radiusCount,
        device const float *events, int eventCount, float4 drag, float shrine, float duration,
        float2 lightShift, float translucency) {
    if (source.a <= 0 || radiusCount < 3) return source;
    float2 uv = (position - bounds.xy) / max(bounds.zw, float2(1));
    float h = jellyHeight(uv, radii, radiusCount, events, eventCount, drag, duration);
    float e = .003;
    float dx = jellyHeight(uv+float2(e,0),radii,radiusCount,events,eventCount,drag,duration)
             - jellyHeight(uv-float2(e,0),radii,radiusCount,events,eventCount,drag,duration);
    float dy = jellyHeight(uv+float2(0,e),radii,radiusCount,events,eventCount,drag,duration)
             - jellyHeight(uv-float2(0,e),radii,radiusCount,events,eventCount,drag,duration);
    float3 normal = normalize(float3(-dx/(2*e), -dy/(2*e), 1));
    float3 pigment = mix(float3(197.0/255,229.0/255,216.0/255), base, drag.w);
    float arrivalLight = 0.0;
    for (int i=0; i+8<eventCount; i+=9) {
        float age = events[i+2];
        float2 p = uv-float2(events[i],events[i+1]);
        float d = length(p);
        // Smooth, broad pigment ribbon: Cartesian phase stays continuous at
        // the arrival point, with no polar cusp or pinwheel.
        float bend = p.x + .07*sin(p.y*8.0-age*4.0);
        float ribbon = exp(-pow(bend/(.035+age*.07),2.0));
        float radius = .045 + age*.60;
        float plume = exp(-d*d/(radius*radius)) * (.48+.52*ribbon);
        float amount = events[i+8] * plume * pow(max(0.0,1.0-age/duration),1.3);
        pigment = mix(pigment,float3(events[i+5],events[i+6],events[i+7]),amount*.85);
        // The arriving pigment carries a broad travelling caustic. It rides
        // the soft surface without introducing a new dent or a sharp normal.
        float crest = exp(-pow((d-age*.60)/.13,2.0));
        arrivalLight += events[i+8] * crest * smoothstep(0.0,.08,age)
                      * pow(max(0.0,1.0-age/duration),2.0);
    }
    float thickness = clamp(h/.30,0.0,1.0);
    float3 absorption = exp(-((1.0-pigment)*1.45+.08) * thickness);
    float diffuse = max(0.0,dot(normal,normalize(float3(-.48,-.66,.72))));
    float3 colour = pigment*(.37+.38*diffuse) + absorption*float3(.25,.28,.26);
    // The dark world transmits less ambient light; retain coloured pigment
    // in the belly instead of compensating with a milky white veil.
    colour += shrine * pigment * .10 * thickness;
    float fresnel = .025+.30*pow(1.0-normal.z,3.0);
    colour += fresnel * mix(float3(.70,.92,1),float3(.43,.93,.88),shrine);
    float3 reflected = reflect(float3(0,0,-1),normal);
    float2 window = (reflected.xy-float2(-.44,-.47)-lightShift)/float2(.38,.43);
    float reflection = exp(-dot(window,window));
    colour = mix(colour,float3(.97,1,1),reflection*.44);
    float smallLight = pow(max(0.0,dot(reflected,normalize(float3(.73,-.18,.66)))),18.0);
    colour += float3(.55,.78,.83)*smallLight*.16;
    // A smaller, soft window edge gives the reflection structure. It follows
    // the touch as the jelly rocks, without washing out the coloured belly.
    float2 pane = (reflected.xy-float2(-.65,-.36)-lightShift) / float2(.12,.31);
    float paneReflection = exp(-dot(pane,pane));
    colour += paneReflection * float3(.17,.20,.19);
    // Light collects at the lower shoulder; the thin edge transmits more of
    // the real world while the caustic keeps its distinct jelly thickness.
    float lower = pow(max(0.0,normal.y),3.0) * (1.0-thickness*.6);
    colour += lower * (float3(.18,.22,.14) + pigment * .20);
    float labelClear = smoothstep(.14,.32,distance(uv,float2(.5,.52)));
    colour += .085*tanh(arrivalLight) * labelClear * (float3(.30,.38,.28)+pigment*.55);
    float rim = pow(1.0-normal.z,8.0);
    colour += rim*float3(.10,.14,.12);
    // Opaque paint has its own pigment-led shading, not glass made milky by
    // raising alpha. Its broad highlight still reveals a soft tactile volume.
    float3 rimTint = mix(float3(.70,.92,1),float3(.43,.93,.88),shrine);
    float3 paint = pigment*(.62+.38*diffuse) + pigment*.025*shrine;
    paint += .05*fresnel*rimTint;
    paint = mix(paint,float3(.97,1,1),reflection*.22);
    paint += paneReflection*float3(.06,.065,.055) + lower*pigment*.09;
    paint += .055*tanh(arrivalLight)*labelClear*(float3(.15,.19,.14)+pigment*.7);
    float material = clamp(translucency,0.0,1.0);
    colour = mix(paint,colour,material);
    // The Jelly endpoint preserves real transmission through the current world.
    float opacity = mix(.32,.60,smoothstep(0.0,.85,thickness));
    opacity += .08*pow(1.0-normal.z,2.0) + reflection*.09;
    float alpha = float(source.a)*mix(1.0,clamp(opacity*mix(.86,1.0,drag.w),.32,.78),material);
    return half4(half3(clamp(colour,0.0,1.0))*half(alpha),half(alpha));
}
