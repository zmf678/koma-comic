using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Media;
using System.Windows.Media.Effects;
using System.Windows.Media.Imaging;

namespace KomaComic.Windows
{
    internal sealed partial class KomaMainWindow
    {
        private LiquidGlassSurface _readerTranslationBall;
        private readonly Dictionary<int, ReaderTranslationWork> _readerTranslationWork = new Dictionary<int, ReaderTranslationWork>();
        private int _translationVisibleFirst = -1;
        private int _translationVisibleLast = -1;
        private int _translationPrefetchAnchor = -1;
        private int _translationPrefetchVersion;

        private sealed class ReaderTranslationResult
        {
            public BitmapSource Image;
            public bool Changed;
        }

        private sealed class ReaderTranslationWork
        {
            public readonly CancellationTokenSource Cancellation = new CancellationTokenSource();
            public TranslationRequestPriority Priority;
            public Task<ReaderTranslationResult> Task;
        }

        private LiquidGlassSurface MakeTranslationBall()
        {
            var ballBtn = new Button
            {
                Content = "译", Width = 50, Height = 50,
                Background = Brushes.Transparent, BorderThickness = new Thickness(0),
                Foreground = Brushes.White, FontSize = 19, FontWeight = FontWeights.SemiBold,
                Cursor = System.Windows.Input.Cursors.Hand, FocusVisualStyle = null,
                ToolTip = "翻译菜单"
            };
            AutomationProperties.SetName(ballBtn, "翻译菜单");
            var surface = new FrameworkElementFactory(typeof(Border));
            surface.Name = "Glass";
            surface.SetValue(Border.BackgroundProperty, Brushes.Transparent);
            surface.SetValue(Border.CornerRadiusProperty, new CornerRadius(25));
            var text = new FrameworkElementFactory(typeof(ContentPresenter));
            text.SetValue(ContentPresenter.HorizontalAlignmentProperty, HorizontalAlignment.Center);
            text.SetValue(ContentPresenter.VerticalAlignmentProperty, VerticalAlignment.Center);
            text.SetValue(UIElement.EffectProperty, new DropShadowEffect { Color = Colors.Black, ShadowDepth = 1, BlurRadius = 5, Opacity = 0.72 });
            surface.AppendChild(text);
            var template = new ControlTemplate(typeof(Button)) { VisualTree = surface };
            ballBtn.Template = template;

            // High-clarity crystal Liquid Glass lens (ClearLens preset: 0 frost, crisp edge-only SDF refraction + dispersion)
            var glassOrb = new LiquidGlassSurface(_readerViewport, LiquidGlassPreset.ClearLens, 25, true, true)
            {
                Width = 50,
                Height = 50,
                Margin = new Thickness(0, 0, 16, 0),
                HorizontalAlignment = HorizontalAlignment.Right,
                VerticalAlignment = VerticalAlignment.Center,
                Visibility = Visibility.Collapsed,
                Child = ballBtn
            };

            ballBtn.Click += delegate
            {
                bool image = _services.TranslationModel.AcceptsPageImage;
                var menu = new ContextMenu { PlacementTarget = glassOrb, Placement = PlacementMode.Left };
                AddTranslationMenuItem(menu, image ? "翻译当前页" : "输入原文", delegate
                {
                    if (image) { _readerTranslationEpoch++; QueueVisibleTranslation(); }
                    else OpenTextTranslation();
                });
                AddTranslationMenuItem(menu, "显示原文", ToggleReaderTranslation);
                AddTranslationMenuItem(menu, "翻译设置", OpenReaderTranslationSettings);
                AddTranslationMenuItem(menu, "查看 Token 用量", OpenTranslationUsage);
                glassOrb.ContextMenu = menu;
                menu.IsOpen = true;
            };
            return glassOrb;
        }

        private static void AddTranslationMenuItem(ContextMenu menu, string label, Action action)
        {
            var item = new MenuItem { Header = label };
            item.Click += delegate { action(); };
            menu.Items.Add(item);
        }

        private void OpenReaderTranslationSettings()
        {
            var dialog = CreateStyledDialog("翻译设置", 405, 240);
            var body = new StackPanel { Margin = new Thickness(20) };
            body.Children.Add(StatText("预翻译后续页", 16, false, true));
            body.Children.Add(StatText("开启图片翻译时，提前翻译本章后续页面。", 13, true));
            var count = new ComboBox
            {
                SelectedIndex = _services.TranslationPrefetchPages, MinHeight = 35, Margin = new Thickness(0, 9, 0, 14),
                ItemsSource = new[] { "关闭", "后续 1 页", "后续 2 页", "后续 3 页", "后续 4 页", "后续 5 页" }
            };
            body.Children.Add(count);
            var footer = new StackPanel { Orientation = Orientation.Horizontal };
            AddBrowseButton(footer, "保存", delegate
            {
                _services.TranslationPrefetchPages = Math.Max(0, count.SelectedIndex);
                _services.SaveState();
                _translationPrefetchVersion++;
                _translationPrefetchAnchor = -1;
                if (_translationVisibleFirst >= 0)
                {
                    PruneReaderTranslations(_translationVisibleFirst, _translationVisibleLast + _services.TranslationPrefetchPages);
                    PrefetchReaderTranslations();
                }
                dialog.Close();
            });
            AddBrowseButton(footer, "取消", dialog.Close);
            body.Children.Add(footer);
            dialog.Content = body;
            dialog.ShowDialog();
        }

        private void OpenTextTranslation()
        {
            var dialog = CreateStyledDialog("文字翻译", 540, 445);
            var fields = new StackPanel { Margin = new Thickness(20) };
            fields.Children.Add(StatText("原文", 14, false, true));
            TextBox input = MakeStyledTextBox();
            input.AcceptsReturn = true;
            input.TextWrapping = TextWrapping.Wrap;
            input.VerticalScrollBarVisibility = ScrollBarVisibility.Auto;
            input.Height = 104;
            input.MaxLength = 8000;
            fields.Children.Add(input);
            fields.Children.Add(StatText("译文", 14, false, true));
            TextBox output = MakeStyledTextBox();
            output.IsReadOnly = true;
            output.TextWrapping = TextWrapping.Wrap;
            output.VerticalScrollBarVisibility = ScrollBarVisibility.Auto;
            output.Height = 116;
            fields.Children.Add(output);
            var actions = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 10, 0, 0) };
            Button translate = UiTheme.MakeButton("翻译", true, false);
            actions.Children.Add(translate);
            AddBrowseButton(actions, "关闭", dialog.Close);
            fields.Children.Add(actions);
            var cancellation = new CancellationTokenSource();
            dialog.Closed += delegate { cancellation.Cancel(); };
            translate.Click += async delegate
            {
                string source = input.Text.Trim();
                if (source.Length == 0) { output.Text = "请输入原文"; return; }
                translate.IsEnabled = false;
                output.Text = "正在翻译…";
                try
                {
                    string result = await MangaTranslation.TranslateTextAsync(_services.TranslationConfig,
                        _services.TranslationModel, source, _services.DataDir, cancellation.Token);
                    if (dialog.IsVisible) output.Text = result;
                }
                catch (Exception error) { if (dialog.IsVisible) output.Text = MangaTranslation.Describe(error); }
                finally { translate.IsEnabled = true; }
            };
            dialog.Content = fields;
            dialog.ShowDialog();
            cancellation.Dispose();
        }

        private void UpdateTranslationWindow(int first, int last)
        {
            if (first == _translationVisibleFirst && last == _translationVisibleLast) return;
            _translationVisibleFirst = first;
            _translationVisibleLast = last;
            _translationPrefetchAnchor = -1;
            _translationPrefetchVersion++;
            PruneReaderTranslations(first, last + _services.TranslationPrefetchPages);
        }

        private void PruneReaderTranslations(int first, int last)
        {
            var remove = _readerTranslationWork.Where(entry =>
                entry.Key > last || (entry.Key < first &&
                    !(first <= last && entry.Key >= first - 2 && entry.Value.Task.Status == TaskStatus.RanToCompletion)));
            foreach (int page in remove.Select(entry => entry.Key).ToArray())
                RemoveReaderTranslation(page);
        }

        private void RemoveReaderTranslation(int page)
        {
            ReaderTranslationWork entry = _readerTranslationWork[page];
            _readerTranslationWork.Remove(page);
            entry.Cancellation.Cancel();
            entry.Task.ContinueWith(delegate { entry.Cancellation.Dispose(); }, TaskScheduler.Default);
        }

        private ReaderTranslationWork GetReaderTranslation(int position, BitmapImage original = null)
        {
            ReaderTranslationWork work;
            if (_readerTranslationWork.TryGetValue(position, out work))
            {
                if (!work.Task.IsCanceled && !work.Task.IsFaulted)
                {
                    if (original != null) work.Priority.Promote();
                    return work;
                }
                RemoveReaderTranslation(position);
            }
            work = new ReaderTranslationWork { Priority = new TranslationRequestPriority(original == null) };
            work.Task = TranslateReaderPage(position, original, _readerPages, work.Cancellation.Token, work.Priority);
            _readerTranslationWork[position] = work;
            return work;
        }

        private async Task<ReaderTranslationResult> TranslateReaderPage(int position, BitmapImage original,
            List<PageImageModel> pages, CancellationToken token, TranslationRequestPriority priority)
        {
            BitmapImage bitmap = original ?? await LoadReaderBitmapAsync(pages[position], position, pages);
            token.ThrowIfCancellationRequested();
            if (bitmap == null) throw new ArgumentException("无法加载漫画页");
            TranslationModel model = _services.TranslationModel;
            string session = model.UsesTranslationServer ? TranslationModel.DecryptSecret(_services.TranslationSession) : "";
            BitmapSource translated = await MangaTranslation.TranslateAsync(bitmap, _services.TranslationServer,
                _services.TranslationConfig, model, session, Path.Combine(_services.DataDir, "cache", "translations"), token, _services.DataDir, priority);
            return new ReaderTranslationResult { Image = translated, Changed = !ReferenceEquals(translated, bitmap) };
        }

        private async void PrefetchReaderTranslations()
        {
            int last = _translationVisibleLast;
            int count = _services.TranslationPrefetchPages;
            if (!_readerTranslationEnabled || !_readerOpen || !_services.TranslationModel.AcceptsPageImage || count == 0 ||
                last < 0 || last == _translationPrefetchAnchor) return;
            ReaderTranslationWork current;
            if (!_readerTranslationWork.TryGetValue(last, out current) || current.Task.Status != TaskStatus.RanToCompletion) return;
            _translationPrefetchAnchor = last;
            int version = _translationPrefetchVersion;
            int end = Math.Min(last + count, _readerPages.Count - 1);
            for (int page = last + 1; page <= end && version == _translationPrefetchVersion; page++)
            {
                try { await GetReaderTranslation(page).Task; }
                catch (Exception) { break; }
            }
        }
    }
}
