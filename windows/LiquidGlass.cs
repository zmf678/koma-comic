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
            Color tint;
            Color specular;
            Color secondary;

            switch (_preset)
            {
                case LiquidGlassPreset.ClearLens:
                    // High-clarity optical glass lens (translation ball / floating orb)
                    refraction = 19.0;
                    chroma = 3.0;
                    boost = 1.20;
                    rimIntensity = 1.28;
                    innerShadow = 0.24;
                    sheenStrength = 0.26;
                    tint = Color.FromArgb(98, 26, 48, 82);
                    specular = Color.FromArgb(248, 255, 255, 255);
                    secondary = Color.FromArgb(204, 128, 208, 255);
                    break;

                case LiquidGlassPreset.ReaderCapsule:
                    refraction = 18.0;
                    chroma = 2.8;
                    boost = 1.10;
                    rimIntensity = 1.18;
                    innerShadow = 0.22;
                    sheenStrength = 0.20;
                    tint = Color.FromArgb(136, 15, 23, 38);
                    specular = Color.FromArgb(232, 255, 255, 255);
                    secondary = Color.FromArgb(168, 122, 198, 255);
                    break;

                case LiquidGlassPreset.ReaderDrawer:
                    refraction = 14.0;
                    chroma = 2.0;
                    boost = 0.95;
                    rimIntensity = 0.96;
                    innerShadow = 0.16;
                    sheenStrength = 0.14;
                    tint = Color.FromArgb(178, 14, 21, 35);
                    specular = Color.FromArgb(204, 255, 255, 255);
                    secondary = Color.FromArgb(136, 118, 186, 255);
                    break;

                case LiquidGlassPreset.FloatingDock:
                    refraction = 17.0;
                    chroma = 2.6;
                    boost = 1.12;
                    rimIntensity = dark ? 1.18 : 1.28;
                    innerShadow = dark ? 0.18 : 0.10;
                    sheenStrength = dark ? 0.20 : 0.34;
                    tint = dark ? Color.FromArgb(116, 18, 29, 50) : Color.FromArgb(134, 246, 251, 255);
                    specular = dark ? Color.FromArgb(234, 255, 255, 255) : Color.FromArgb(252, 255, 255, 255);
                    secondary = dark ? Color.FromArgb(166, 118, 194, 255) : Color.FromArgb(182, 142, 196, 255);
                    break;

                case LiquidGlassPreset.SubtlePill:
                    refraction = 12.0;
                    chroma = 2.0;
                    boost = 1.08;
                    rimIntensity = 1.08;
                    innerShadow = dark ? 0.15 : 0.08;
                    sheenStrength = dark ? 0.16 : 0.26;
                    tint = dark ? Color.FromArgb(128, 18, 26, 44) : Color.FromArgb(146, 248, 252, 255);
                    specular = Color.FromArgb(224, 255, 255, 255);
                    secondary = Color.FromArgb(152, 126, 196, 255);
                    break;

                default: // FrostedBar (search bar, unified browse bar)
                    refraction = 15.5;
                    chroma = 2.4;
                    boost = 1.08;
                    rimIntensity = dark ? 1.12 : 1.24;
                    innerShadow = dark ? 0.15 : 0.08;
                    sheenStrength = dark ? 0.18 : 0.30;
                    tint = dark ? Color.FromArgb(124, 18, 28, 48) : Color.FromArgb(144, 248, 251, 255);
                    specular = dark ? Color.FromArgb(222, 255, 255, 255) : Color.FromArgb(250, 255, 255, 255);
                    secondary = dark ? Color.FromArgb(156, 122, 194, 255) : Color.FromArgb(174, 146, 200, 255);
                    break;
            }

            double contrast = 1.0 + 0.14 * boost;
            double bias = 0.5 - 0.5 * contrast + 0.035 * boost;
            _shaderEffect.OpticalParams = new Point4D(refraction, chroma, contrast, bias);
            Point lightDir = ComputeLightDirection(_lightU, _lightV);
            _shaderEffect.LightParams = new Point4D(lightDir.X, lightDir.Y, rimIntensity, innerShadow);
            _shaderEffect.TintColorVec = LiquidGlassEffect.ToVec(tint);
            _shaderEffect.SpecularColorVec = LiquidGlassEffect.ToPremulVec(specular, sheenStrength);
            _shaderEffect.SecondaryRimColorVec = LiquidGlassEffect.ToPremulVec(secondary, 0.0);
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
                case LiquidGlassPreset.SubtlePill: return 4.5;
                case LiquidGlassPreset.FloatingDock: return 7.5;
                case LiquidGlassPreset.FrostedBar: return 7.0;
                case LiquidGlassPreset.ReaderCapsule: return 8.0;
                case LiquidGlassPreset.ReaderDrawer: return 11.0;
                default: return 7.0;
            }
        }

        private static double GetBevelWidth(LiquidGlassPreset preset, double w, double h, double radius)
        {
            double minDim = Math.Min(w, h);
            switch (preset)
            {
                case LiquidGlassPreset.ClearLens:
                    return Math.Max(10.0, Math.Min(minDim * 0.44, 22.0));
                case LiquidGlassPreset.ReaderDrawer:
                    return 22.0;
                case LiquidGlassPreset.SubtlePill:
                    return Math.Max(8.0, Math.Min(minDim * 0.36, 15.0));
                default:
                    return Math.Max(11.0, Math.Min(minDim * 0.34, Math.Max(18.0, radius * 0.62)));
            }
        }

        private static DropShadowEffect CreateFloatingShadow(bool dark, LiquidGlassPreset preset)
        {
            bool elevated = preset == LiquidGlassPreset.FloatingDock || preset == LiquidGlassPreset.ReaderCapsule || preset == LiquidGlassPreset.ClearLens;
            return new DropShadowEffect
            {
                Color = dark ? Color.FromRgb(2, 6, 14) : Color.FromRgb(42, 72, 118),
                BlurRadius = elevated ? 28 : 18,
                ShadowDepth = elevated ? 7 : 4,
                Direction = 270,
                Opacity = dark ? (elevated ? 0.50 : 0.34) : (elevated ? 0.16 : 0.10)
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
