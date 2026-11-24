using System;
using System.Collections.Generic;
using System.IO;
using System.Reflection;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Effects;
using System.Windows.Media.Media3D;
using System.Windows.Shapes;

namespace KomaComic.Windows
{
    internal enum LiquidGlassPreset
    {
        FloatingDock,
        FrostedBar,
        ReaderCapsule,
        ReaderDrawer,
        ClearLens,
        SubtlePill
    }

    internal sealed class LiquidGlassEffect : ShaderEffect
    {
        private static readonly PixelShader SharedShader;
        private static readonly bool ShaderSupported;

        static LiquidGlassEffect()
        {
            try
            {
                if (RenderCapability.IsPixelShaderVersionSupported(2, 0))
                {
                    Assembly asm = Assembly.GetExecutingAssembly();
                    Stream stream = asm.GetManifestResourceStream("LiquidGlass_ps2.ps");
                    if (stream != null)
                    {
                        using (stream)
                        {
                            PixelShader ps = new PixelShader();
                            ps.SetStreamSource(stream);
                            ps.Freeze();
                            SharedShader = ps;
                            ShaderSupported = true;
                        }
                    }
                }
            }
            catch
            {
                ShaderSupported = false;
            }
        }

        public static bool IsSupported { get { return ShaderSupported && SharedShader != null; } }

        public static readonly DependencyProperty InputProperty =
            RegisterPixelShaderSamplerProperty("Input", typeof(LiquidGlassEffect), 0);

        public static readonly DependencyProperty ElementMetricsProperty =
            DependencyProperty.Register("ElementMetrics", typeof(Point4D), typeof(LiquidGlassEffect),
                new UIPropertyMetadata(new Point4D(100, 100, 24, 1.0 / 18.0), PixelShaderConstantCallback(0)));

        public static readonly DependencyProperty OpticalParamsProperty =
            DependencyProperty.Register("OpticalParams", typeof(Point4D), typeof(LiquidGlassEffect),
                new UIPropertyMetadata(new Point4D(16, 2.5, 1.12, -0.03), PixelShaderConstantCallback(1)));

        public static readonly DependencyProperty LightParamsProperty =
            DependencyProperty.Register("LightParams", typeof(Point4D), typeof(LiquidGlassEffect),
                new UIPropertyMetadata(new Point4D(-0.68, -0.73, 1.15, 0.16), PixelShaderConstantCallback(2)));

        public static readonly DependencyProperty TintColorVecProperty =
            DependencyProperty.Register("TintColorVec", typeof(Point4D), typeof(LiquidGlassEffect),
                new UIPropertyMetadata(new Point4D(0.10, 0.16, 0.28, 0.45), PixelShaderConstantCallback(3)));

        public static readonly DependencyProperty SpecularColorVecProperty =
            DependencyProperty.Register("SpecularColorVec", typeof(Point4D), typeof(LiquidGlassEffect),
                new UIPropertyMetadata(new Point4D(0.92, 0.92, 0.92, 0.20), PixelShaderConstantCallback(4)));

        public static readonly DependencyProperty SecondaryRimColorVecProperty =
            DependencyProperty.Register("SecondaryRimColorVec", typeof(Point4D), typeof(LiquidGlassEffect),
                new UIPropertyMetadata(new Point4D(0.38, 0.56, 0.72, 0.0), PixelShaderConstantCallback(5)));

        public static readonly DependencyProperty PrecompProperty =
            DependencyProperty.Register("Precomp", typeof(Point4D), typeof(LiquidGlassEffect),
                new UIPropertyMetadata(new Point4D(0.01, 0.01, 26.0, 26.0), PixelShaderConstantCallback(6)));

        public LiquidGlassEffect()
        {
            if (SharedShader != null)
            {
                PixelShader = SharedShader;
                UpdateShaderValue(InputProperty);
                UpdateShaderValue(ElementMetricsProperty);
                UpdateShaderValue(OpticalParamsProperty);
                UpdateShaderValue(LightParamsProperty);
                UpdateShaderValue(TintColorVecProperty);
                UpdateShaderValue(SpecularColorVecProperty);
                UpdateShaderValue(SecondaryRimColorVecProperty);
                UpdateShaderValue(PrecompProperty);
            }
        }

        public Brush Input
        {
            get { return (Brush)GetValue(InputProperty); }
            set { SetValue(InputProperty, value); }
        }

        public Point4D ElementMetrics
        {
            get { return (Point4D)GetValue(ElementMetricsProperty); }
            set { SetValue(ElementMetricsProperty, value); }
        }

        public Point4D OpticalParams
        {
            get { return (Point4D)GetValue(OpticalParamsProperty); }
            set { SetValue(OpticalParamsProperty, value); }
        }

        public Point4D LightParams
        {
            get { return (Point4D)GetValue(LightParamsProperty); }
            set { SetValue(LightParamsProperty, value); }
        }

        public Point4D TintColorVec
        {
            get { return (Point4D)GetValue(TintColorVecProperty); }
            set { SetValue(TintColorVecProperty, value); }
        }

        public Point4D SpecularColorVec
        {
            get { return (Point4D)GetValue(SpecularColorVecProperty); }
            set { SetValue(SpecularColorVecProperty, value); }
        }

        public Point4D SecondaryRimColorVec
        {
            get { return (Point4D)GetValue(SecondaryRimColorVecProperty); }
            set { SetValue(SecondaryRimColorVecProperty, value); }
        }

        public Point4D Precomp
        {
            get { return (Point4D)GetValue(PrecompProperty); }
            set { SetValue(PrecompProperty, value); }
        }

        public static Point4D ToVec(Color c, double alphaOverride = -1)
        {
            double a = alphaOverride >= 0 ? alphaOverride : (c.A / 255.0);
            return new Point4D(c.R / 255.0, c.G / 255.0, c.B / 255.0, a);
        }

        public static Point4D ToPremulVec(Color c, double wSlot)
        {
            double a = c.A / 255.0;
            return new Point4D((c.R / 255.0) * a, (c.G / 255.0) * a, (c.B / 255.0) * a, wSlot);
        }
    }

    internal static class LiquidGlassRegistry
    {
        private static readonly List<WeakReference> Surfaces = new List<WeakReference>();

        public static void Register(LiquidGlassSurface surface)
        {
            if (surface == null) return;
            PruneAndAdd(surface);
        }

        public static void RefreshAll(bool dark, bool glassEnabled)
        {
            for (int i = Surfaces.Count - 1; i >= 0; i--)
            {
                LiquidGlassSurface s = Surfaces[i].Target as LiquidGlassSurface;
                if (s == null) { Surfaces.RemoveAt(i); continue; }
                s.ApplyMaterialState(dark, glassEnabled);
            }
        }

        public static void InvalidateBackdrops()
        {
            for (int i = Surfaces.Count - 1; i >= 0; i--)
            {
                LiquidGlassSurface s = Surfaces[i].Target as LiquidGlassSurface;
                if (s == null) { Surfaces.RemoveAt(i); continue; }
                s.SyncBackdropBounds(true);
            }
        }

        private static void PruneAndAdd(LiquidGlassSurface surface)
        {
            for (int i = Surfaces.Count - 1; i >= 0; i--)
            {
                object t = Surfaces[i].Target;
                if (t == null) Surfaces.RemoveAt(i);
                else if (ReferenceEquals(t, surface)) return;
            }
            Surfaces.Add(new WeakReference(surface));
        }
    }

    /// <summary>
    /// Reusable WPF Liquid Glass surface implementing the WWDC25 optical model:
    /// live VisualBrush backdrop sampling + optional Gaussian frost + GPU HLSL SDF
    /// edge-only refraction, subtle bevel dispersion, Fresnel dual-rim specular,
    /// inner depth shadow, floating elevation shadow, and pointer-reactive glint.
    /// </summary>
    internal sealed class LiquidGlassSurface : Grid
    {
        private readonly LiquidGlassPreset _preset;
        private readonly bool _forceDark;
        private readonly double _cornerRadius;
        private readonly bool _interactiveElastic;
        private Visual _backdropSource;

        private readonly Grid _shadowWrapper;
        private readonly Grid _glassClipHost;
        private readonly Grid _blurClip;
        private readonly Rectangle _backdropRect;
        private readonly VisualBrush _backdropBrush;
        private readonly BlurEffect _preBlur;
        private readonly double _blurPad;
        private readonly LiquidGlassEffect _shaderEffect;
        private readonly Border _fallbackBorder;
        private readonly Border _contentHost;
        private readonly ScaleTransform _elasticScale;

        private Rect _lastBounds = Rect.Empty;
        private bool _glassActive;
        private bool _darkActive;
        private double _lightU = 0.18;
        private double _lightV = 0.12;
        private bool _hovering;

        public Border ContentHost { get { return _contentHost; } }

        public UIElement Child
        {
            get { return _contentHost.Child; }
            set { _contentHost.Child = value; }
        }

        public Thickness ContentPadding
        {
            get { return _contentHost.Padding; }
            set { _contentHost.Padding = value; }
        }

        public LiquidGlassSurface(
            Visual backdropSource,
            LiquidGlassPreset preset,
            double cornerRadius,
            bool forceDark = false,
            bool interactiveElastic = false)
        {
            _backdropSource = backdropSource;
            _preset = preset;
            _cornerRadius = cornerRadius;
            _forceDark = forceDark;
            _interactiveElastic = interactiveElastic;
            ClipToBounds = false;

            _elasticScale = new ScaleTransform(1.0, 1.0);
            if (_interactiveElastic)
            {
                RenderTransformOrigin = new Point(0.5, 0.5);
                RenderTransform = _elasticScale;
            }

            // 1. Fallback flat/classic themed plate (when GlassMaterial == false or no shader support)
            _fallbackBorder = new Border
            {
                CornerRadius = new CornerRadius(cornerRadius),
                BorderThickness = new Thickness(1),
                IsHitTestVisible = false
            };

            // 2. Live GPU Liquid Glass backdrop layer (padded Viewbox eliminates BlurEffect edge fade)
            double preBlurRadius = GetPreBlurRadius(preset);
            _blurPad = preBlurRadius > 0.1 ? (preBlurRadius + 8.0) : 0.0;

            _backdropBrush = new VisualBrush
            {
                Visual = backdropSource,
                Stretch = Stretch.Fill,
                ViewboxUnits = BrushMappingMode.Absolute,
                AutoLayoutContent = false
            };
            RenderOptions.SetCachingHint(_backdropBrush, CachingHint.Cache);

            _preBlur = new BlurEffect
            {
                Radius = preBlurRadius,
                KernelType = KernelType.Gaussian,
                RenderingBias = RenderingBias.Performance
            };

            _backdropRect = new Rectangle
            {
                Fill = _backdropBrush,
                Margin = new Thickness(-_blurPad),
                IsHitTestVisible = false
            };
            if (preBlurRadius > 0.1)
            {
                _backdropRect.Effect = _preBlur;
            }

            _shaderEffect = LiquidGlassEffect.IsSupported ? new LiquidGlassEffect() : null;

            // Inner clip confines the padded blurred rectangle to exact (0, 0, w, h) BEFORE ShaderEffect computes UV
            _blurClip = new Grid
            {
                ClipToBounds = true,
                IsHitTestVisible = false
            };
            _blurClip.Children.Add(_backdropRect);

            _glassClipHost = new Grid
            {
                ClipToBounds = true,
                IsHitTestVisible = false,
                SnapsToDevicePixels = false
            };
            _glassClipHost.Children.Add(_blurClip);
            if (_shaderEffect != null)
            {
                _glassClipHost.Effect = _shaderEffect;
            }

            // 3. Outer floating shadow wrapper around the opaque-alpha SDF glass silhouette
            _shadowWrapper = new Grid
            {
                ClipToBounds = false,
                IsHitTestVisible = false
            };
            _shadowWrapper.Children.Add(_glassClipHost);

            // 4. Crisp unblurred foreground content layer
            _contentHost = new Border
            {
                CornerRadius = new CornerRadius(cornerRadius),
                Background = Brushes.Transparent
            };

            Children.Add(_fallbackBorder);
            Children.Add(_shadowWrapper);
            Children.Add(_contentHost);

            Loaded += delegate { SyncBackdropBounds(true); };
            SizeChanged += delegate { SyncBackdropBounds(true); };
            LayoutUpdated += delegate { if (_glassActive && IsVisible) SyncBackdropBounds(false); };
            IsVisibleChanged += delegate { if (IsVisible) SyncBackdropBounds(true); };

            MouseEnter += OnGlassMouseEnter;
            MouseMove += OnGlassMouseMove;
            MouseLeave += OnGlassMouseLeave;
            PreviewMouseLeftButtonDown += OnGlassPressDown;
            PreviewMouseLeftButtonUp += OnGlassPressUp;

            LiquidGlassRegistry.Register(this);
            ApplyMaterialState(UiTheme.IsDarkActive, UiTheme.IsGlassActive);
        }

        public void SetBackdropSource(Visual source)
        {
            _backdropSource = source;
            _backdropBrush.Visual = source;
            SyncBackdropBounds(true);
        }

        public void ApplyMaterialState(bool dark, bool glassEnabled)
        {
            bool effectiveDark = _forceDark || dark;
            _darkActive = effectiveDark;
            bool useShader = glassEnabled && LiquidGlassEffect.IsSupported && _backdropSource != null;
            _glassActive = useShader;

            if (useShader)
            {
                _shadowWrapper.Visibility = Visibility.Visible;
                _shadowWrapper.Effect = CreateFloatingShadow(effectiveDark, _preset);
                _fallbackBorder.Visibility = Visibility.Collapsed;
                ApplyShaderTuning(effectiveDark);
                SyncBackdropBounds(true);
            }
            else
            {
                _shadowWrapper.Visibility = Visibility.Collapsed;
                _shadowWrapper.Effect = null;
                _fallbackBorder.Visibility = Visibility.Visible;
                ApplyFallbackStyling(effectiveDark, glassEnabled);
            }
        }

        public void SyncBackdropBounds(bool force)
        {
            if (!_glassActive || _backdropSource == null || !IsVisible) return;
            double w = ActualWidth;
            double h = ActualHeight;
            if (w < 2.0 || h < 2.0) return;

            try
            {
                PresentationSource src = PresentationSource.FromVisual(this);
                PresentationSource bsrc = PresentationSource.FromVisual(_backdropSource);
                if (src == null || bsrc == null || !ReferenceEquals(src, bsrc)) return;

                GeneralTransform transform = TransformToVisual(_backdropSource);
                Rect bounds = transform.TransformBounds(new Rect(0, 0, w, h));
                if (double.IsNaN(bounds.X) || double.IsNaN(bounds.Y) || bounds.Width < 1 || bounds.Height < 1) return;

                if (!force &&
                    Math.Abs(bounds.X - _lastBounds.X) < 0.35 &&
                    Math.Abs(bounds.Y - _lastBounds.Y) < 0.35 &&
                    Math.Abs(bounds.Width - _lastBounds.Width) < 0.35 &&
                    Math.Abs(bounds.Height - _lastBounds.Height) < 0.35)
                {
                    return;
                }

                _lastBounds = bounds;
                // Expand viewbox by _blurPad on all sides to match _backdropRect negative margin
                _backdropBrush.Viewbox = new Rect(
                    bounds.X - _blurPad,
                    bounds.Y - _blurPad,
                    bounds.Width + _blurPad * 2.0,
                    bounds.Height + _blurPad * 2.0);

                if (_shaderEffect != null)
                {
                    double clampedR = Math.Min(_cornerRadius, Math.Min(w, h) * 0.5);
                    double bevel = GetBevelWidth(_preset, w, h, clampedR);
                    _blurClip.Clip = new RectangleGeometry(new Rect(0, 0, w, h));
                    _shaderEffect.ElementMetrics = new Point4D(w, h, clampedR, 1.0 / Math.Max(bevel, 2.0));
                    _shaderEffect.Precomp = new Point4D(1.0 / w, 1.0 / h, w * 0.5 - clampedR, h * 0.5 - clampedR);
                    _glassClipHost.Clip = new RectangleGeometry(new Rect(0, 0, w, h), clampedR, clampedR);
                }
            }
            catch
            {
                // Ignore transient visual tree detachments during layout transitions
            }
        }

        private void ApplyShaderTuning(bool dark)
        {
            if (_shaderEffect == null) return;

            double refraction;
            double chroma;
            double boost;
            double rimIntensity;
            double innerShadow;
            double sheenStrength;
            double outerDarkLip;
            Color tint;
            Color specular;
            Color secondary;

            switch (_preset)
            {
                case LiquidGlassPreset.ClearLens:
                    // High-clarity optical glass lens (translation ball / floating orb)
                    refraction = 26.0;
                    chroma = 3.8;
                    boost = 1.24;
                    rimIntensity = 1.32;
                    innerShadow = 0.24;
                    sheenStrength = 0.30;
                    outerDarkLip = 0.14;
                    tint = Color.FromArgb(88, 24, 46, 82);
                    specular = Color.FromArgb(250, 255, 255, 255);
                    secondary = Color.FromArgb(215, 118, 204, 255);
                    break;

                case LiquidGlassPreset.ReaderCapsule:
                    refraction = 26.0;
                    chroma = 3.4;
                    boost = 1.15;
                    rimIntensity = 1.24;
                    innerShadow = 0.24;
                    sheenStrength = 0.24;
                    outerDarkLip = 0.12;
                    tint = Color.FromArgb(118, 14, 22, 38);
                    specular = Color.FromArgb(242, 255, 255, 255);
                    secondary = Color.FromArgb(184, 110, 194, 255);
                    break;

                case LiquidGlassPreset.ReaderDrawer:
                    refraction = 18.0;
                    chroma = 2.4;
                    boost = 1.00;
                    rimIntensity = 1.05;
                    innerShadow = 0.18;
                    sheenStrength = 0.16;
                    outerDarkLip = 0.10;
                    tint = Color.FromArgb(166, 14, 21, 35);
                    specular = Color.FromArgb(216, 255, 255, 255);
                    secondary = Color.FromArgb(152, 112, 184, 255);
                    break;

                case LiquidGlassPreset.FloatingDock:
                    // Ultra-translucent curved Liquid Glass capsule (unmistakable in BOTH Light & Dark mode)
                    refraction = 28.0;
                    chroma = 3.6;
                    boost = dark ? 1.16 : 1.10;
                    rimIntensity = dark ? 1.26 : 1.35;
                    innerShadow = dark ? 0.22 : 0.18;
                    sheenStrength = dark ? 0.24 : 0.42;
                    outerDarkLip = dark ? 0.10 : 0.26;
                    tint = dark ? Color.FromArgb(88, 18, 30, 54) : Color.FromArgb(62, 242, 249, 255);
                    specular = dark ? Color.FromArgb(244, 255, 255, 255) : Color.FromArgb(255, 255, 255, 255);
                    secondary = dark ? Color.FromArgb(188, 108, 192, 255) : Color.FromArgb(214, 96, 166, 255);
                    break;

                case LiquidGlassPreset.SubtlePill:
                    refraction = 22.0;
                    chroma = 3.0;
                    boost = 1.12;
                    rimIntensity = dark ? 1.18 : 1.30;
                    innerShadow = dark ? 0.18 : 0.16;
                    sheenStrength = dark ? 0.20 : 0.36;
                    outerDarkLip = dark ? 0.10 : 0.24;
                    tint = dark ? Color.FromArgb(98, 18, 28, 48) : Color.FromArgb(66, 244, 250, 255);
                    specular = Color.FromArgb(248, 255, 255, 255);
                    secondary = dark ? Color.FromArgb(176, 114, 194, 255) : Color.FromArgb(204, 102, 170, 255);
                    break;

                default: // FrostedBar (floating search bar & unified browse bar)
                    refraction = 26.0;
                    chroma = 3.4;
                    boost = dark ? 1.14 : 1.08;
                    rimIntensity = dark ? 1.22 : 1.32;
                    innerShadow = dark ? 0.20 : 0.17;
                    sheenStrength = dark ? 0.22 : 0.38;
                    outerDarkLip = dark ? 0.10 : 0.25;
                    tint = dark ? Color.FromArgb(96, 18, 29, 50) : Color.FromArgb(68, 244, 250, 255);
                    specular = dark ? Color.FromArgb(240, 255, 255, 255) : Color.FromArgb(255, 255, 255, 255);
                    secondary = dark ? Color.FromArgb(180, 112, 192, 255) : Color.FromArgb(208, 98, 168, 255);
                    break;
            }

            double contrast = 1.0 + 0.16 * boost;
            double bias = 0.5 - 0.5 * contrast + (dark ? 0.035 : 0.020) * boost;
            _shaderEffect.OpticalParams = new Point4D(refraction, chroma, contrast, bias);
            Point lightDir = ComputeLightDirection(_lightU, _lightV);
            _shaderEffect.LightParams = new Point4D(lightDir.X, lightDir.Y, rimIntensity, innerShadow);
            _shaderEffect.TintColorVec = LiquidGlassEffect.ToVec(tint);
            _shaderEffect.SpecularColorVec = LiquidGlassEffect.ToPremulVec(specular, sheenStrength);
            _shaderEffect.SecondaryRimColorVec = LiquidGlassEffect.ToPremulVec(secondary, outerDarkLip);
        }

        private static Point ComputeLightDirection(double u, double v)
        {
            double dx = -0.68 + (u - 0.5) * 0.90;
            double dy = -0.73 + (v - 0.5) * 0.90;
            double len = Math.Sqrt(dx * dx + dy * dy);
            if (len < 0.001) return new Point(-0.68, -0.73);
            return new Point(dx / len, dy / len);
        }

        private void ApplyFallbackStyling(bool dark, bool glassEnabled)
        {
            if (_preset == LiquidGlassPreset.ReaderCapsule || _preset == LiquidGlassPreset.ReaderDrawer || _preset == LiquidGlassPreset.ClearLens)
            {
                LinearGradientBrush bg = new LinearGradientBrush { StartPoint = new Point(0, 0), EndPoint = new Point(1, 1) };
                if (_preset == LiquidGlassPreset.ClearLens)
                {
                    bg.GradientStops.Add(new GradientStop(Color.FromArgb(244, 108, 148, 190), 0));
                    bg.GradientStops.Add(new GradientStop(Color.FromArgb(238, 48, 72, 108), 0.5));
                    bg.GradientStops.Add(new GradientStop(Color.FromArgb(242, 20, 32, 52), 1));
                }
                else
                {
                    bg.GradientStops.Add(new GradientStop(Color.FromArgb(232, 22, 31, 48), 0));
                    bg.GradientStops.Add(new GradientStop(Color.FromArgb(228, 12, 17, 28), 1));
                }
                bg.Freeze();
                _fallbackBorder.Background = bg;
                _fallbackBorder.BorderBrush = new SolidColorBrush(Color.FromArgb(95, 210, 232, 255));
                _fallbackBorder.Effect = CreateFloatingShadow(true, _preset);
                return;
            }

            if (_preset == LiquidGlassPreset.FloatingDock)
            {
                _fallbackBorder.SetResourceReference(Border.BackgroundProperty, "Brush.NavGlass");
                _fallbackBorder.SetResourceReference(Border.BorderBrushProperty, "Brush.NavGlassRim");
                _fallbackBorder.Effect = UiTheme.CreateGlassShadow(dark, false);
            }
            else
            {
                _fallbackBorder.SetResourceReference(Border.BackgroundProperty, "Brush.BgElevated");
                _fallbackBorder.SetResourceReference(Border.BorderBrushProperty, "Brush.Border");
                _fallbackBorder.Effect = glassEnabled ? UiTheme.CreateGlassShadow(dark, true) : null;
            }
        }

        private static double GetPreBlurRadius(LiquidGlassPreset preset)
        {
            switch (preset)
            {
                case LiquidGlassPreset.ClearLens: return 0.0;      // Crystal-clear refractive glass (no frost!)
                case LiquidGlassPreset.SubtlePill: return 2.2;
                case LiquidGlassPreset.FloatingDock: return 3.6;
                case LiquidGlassPreset.FrostedBar: return 3.2;
                case LiquidGlassPreset.ReaderCapsule: return 4.8;
                case LiquidGlassPreset.ReaderDrawer: return 7.5;
                default: return 3.2;
            }
        }

        private static double GetBevelWidth(LiquidGlassPreset preset, double w, double h, double radius)
        {
            double minDim = Math.Min(w, h);
            switch (preset)
            {
                case LiquidGlassPreset.ClearLens:
                    return Math.Max(12.0, Math.Min(minDim * 0.46, 24.0));
                case LiquidGlassPreset.ReaderDrawer:
                    return 24.0;
                case LiquidGlassPreset.SubtlePill:
                    return Math.Max(10.0, Math.Min(minDim * 0.42, 18.0));
                default:
                    return Math.Max(14.0, Math.Min(minDim * 0.42, Math.Max(20.0, radius * 0.72)));
            }
        }

        private static DropShadowEffect CreateFloatingShadow(bool dark, LiquidGlassPreset preset)
        {
            bool elevated = preset == LiquidGlassPreset.FloatingDock || preset == LiquidGlassPreset.ReaderCapsule || preset == LiquidGlassPreset.ClearLens;
            return new DropShadowEffect
            {
                Color = dark ? Color.FromRgb(2, 6, 14) : Color.FromRgb(28, 56, 108),
                BlurRadius = elevated ? 26 : 18,
                ShadowDepth = elevated ? 6 : 4,
                Direction = 270,
                Opacity = dark ? (elevated ? 0.54 : 0.36) : (elevated ? 0.24 : 0.16)
            };
        }

        private void OnGlassMouseEnter(object sender, MouseEventArgs e)
        {
            _hovering = true;
            if (_interactiveElastic && SystemParameters.ClientAreaAnimation)
            {
                AnimateElasticScale(_preset == LiquidGlassPreset.ClearLens ? 1.055 : 1.018, 160);
            }
        }

        private void OnGlassMouseMove(object sender, MouseEventArgs e)
        {
            if (!_glassActive || _shaderEffect == null || ActualWidth < 2 || ActualHeight < 2) return;
            Point pt = e.GetPosition(this);
            _lightU = Math.Max(0.0, Math.Min(1.0, pt.X / ActualWidth));
            _lightV = Math.Max(0.0, Math.Min(1.0, pt.Y / ActualHeight));
            Point4D cur = _shaderEffect.LightParams;
            Point dir = ComputeLightDirection(_lightU, _lightV);
            _shaderEffect.LightParams = new Point4D(dir.X, dir.Y, cur.Z, cur.W);
        }

        private void OnGlassMouseLeave(object sender, MouseEventArgs e)
        {
            _hovering = false;
            _lightU = 0.18;
            _lightV = 0.12;
            if (_shaderEffect != null)
            {
                Point4D cur = _shaderEffect.LightParams;
                Point dir = ComputeLightDirection(_lightU, _lightV);
                _shaderEffect.LightParams = new Point4D(dir.X, dir.Y, cur.Z, cur.W);
            }
            if (_interactiveElastic && SystemParameters.ClientAreaAnimation)
            {
                AnimateElasticScale(1.0, 210);
            }
        }

        private void OnGlassPressDown(object sender, MouseButtonEventArgs e)
        {
            if (_interactiveElastic && SystemParameters.ClientAreaAnimation)
            {
                AnimateElasticScale(0.965, 90);
            }
        }

        private void OnGlassPressUp(object sender, MouseButtonEventArgs e)
        {
            if (_interactiveElastic && SystemParameters.ClientAreaAnimation)
            {
                AnimateElasticScale(_hovering ? (_preset == LiquidGlassPreset.ClearLens ? 1.055 : 1.018) : 1.0, 140);
            }
        }

        private void AnimateElasticScale(double target, int ms)
        {
            Duration d = new Duration(TimeSpan.FromMilliseconds(ms));
            CubicEase ease = new CubicEase { EasingMode = EasingMode.EaseOut };
            _elasticScale.BeginAnimation(ScaleTransform.ScaleXProperty, new DoubleAnimation(target, d) { EasingFunction = ease });
            _elasticScale.BeginAnimation(ScaleTransform.ScaleYProperty, new DoubleAnimation(target, d) { EasingFunction = ease });
        }
    }
}
