using System;
using System.Collections.Generic;
using System.Linq;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Media;

namespace KomaComic.Windows
{
    internal sealed partial class KomaMainWindow
    {
        private static TextBlock StatText(string text, double size, bool muted = false, bool emphasis = false)
        {
            var item = new TextBlock { Text = text, FontSize = size, TextWrapping = TextWrapping.Wrap };
            item.SetResourceReference(TextBlock.ForegroundProperty, muted ? "Brush.TextMuted" : "Brush.Text");
            if (emphasis) item.FontWeight = FontWeights.SemiBold;
            return item;
        }

        private static Border StatPanel(UIElement content)
        {
            var panel = new Border
            {
                CornerRadius = new CornerRadius(16), Padding = new Thickness(20),
                BorderThickness = new Thickness(1), Child = content
            };
            panel.SetResourceReference(Border.BackgroundProperty, "Brush.Surface");
            panel.SetResourceReference(Border.BorderBrushProperty, "Brush.Border");
            return panel;
        }

        private static Grid WeekBars(DateTime[] days, long[] values, DateTime? selected, Func<long, string> format, Action<DateTime> select = null)
        {
            var grid = new Grid { Margin = new Thickness(0, 8, 0, 0) };
            long max = Math.Max(1, values.Max());
            ControlTemplate dayTemplate = select == null ? null : DateChartTemplate();
            for (int index = 0; index < days.Length; index++)
            {
                DateTime day = days[index];
                long amount = values[index];
                bool active = day == selected;
                grid.ColumnDefinitions.Add(new ColumnDefinition());
                var stack = new StackPanel { VerticalAlignment = VerticalAlignment.Bottom };
                var track = new Grid { Height = 94, Margin = new Thickness(0, 2, 0, 5) };
                var bar = new Border
                {
                    CornerRadius = new CornerRadius(4), Width = 16,
                    Height = amount == 0 ? 2 : Math.Max(4, 90.0 * amount / max),
                    HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Bottom,
                    Opacity = selected.HasValue && !active ? 0.65 : 1
                };
                bar.SetResourceReference(Border.BackgroundProperty, amount == 0 ? "Brush.Border" : "Brush.Accent");
                track.Children.Add(bar);
                stack.Children.Add(track);
                var label = StatText(day.ToString("M/d"), 12, !active, active);
                label.TextAlignment = TextAlignment.Center;
                stack.Children.Add(label);
                FrameworkElement cell = stack;
                string detail = day.ToString("M 月 d 日") + " · " + format(amount);
                if (select != null)
                {
                    var button = new Button
                    {
                        Content = stack, Margin = new Thickness(2, 0, 2, 0),
                        Background = Brushes.Transparent, Template = dayTemplate, Cursor = System.Windows.Input.Cursors.Hand
                    };
                    if (active) button.SetResourceReference(Control.BackgroundProperty, "Brush.InputBg");
                    button.IsEnabled = day <= DateTime.Today;
                    button.Click += delegate { select(day); };
                    AutomationProperties.SetName(button, detail);
                    cell = button;
                }
                cell.ToolTip = detail;
                Grid.SetColumn(cell, index);
                grid.Children.Add(cell);
            }
            return grid;
        }

        private static ControlTemplate DateChartTemplate()
        {
            var edge = new FrameworkElementFactory(typeof(Border));
            edge.Name = "Day";
            edge.SetValue(Border.BackgroundProperty, new TemplateBindingExtension(Control.BackgroundProperty));
            edge.SetValue(Border.CornerRadiusProperty, new CornerRadius(8));
            edge.SetValue(Border.PaddingProperty, new Thickness(0, 0, 0, 5));
            var content = new FrameworkElementFactory(typeof(ContentPresenter));
            edge.AppendChild(content);
            var template = new ControlTemplate(typeof(Button)) { VisualTree = edge };
            foreach (DependencyProperty property in new[] { UIElement.IsMouseOverProperty, UIElement.IsKeyboardFocusedProperty })
            {
                var active = new Trigger { Property = property, Value = true };
                active.Setters.Add(new Setter(Border.BorderThicknessProperty, new Thickness(1), "Day"));
                active.Setters.Add(new Setter(Border.BorderBrushProperty, new DynamicResourceExtension("Brush.Accent"), "Day"));
                template.Triggers.Add(active);
            }
            return template;
        }

        private void RenderReadingProfile(List<ReadingEntry> rows, DateTime? selected, bool all)
        {
            _profileBody.Children.Clear();
            var account = new DockPanel { Margin = new Thickness(0, 0, 0, 16), LastChildFill = true };
            var avatar = new Border { Width = 48, Height = 48, CornerRadius = new CornerRadius(24), Child = BuildAvatar(48),
                Margin = new Thickness(0, 0, 13, 0) };
            avatar.SetResourceReference(Border.BackgroundProperty, "Brush.Surface");
            account.Children.Add(avatar);
            Button edit = UiTheme.MakeButton("更换头像", false, false);
            DockPanel.SetDock(edit, Dock.Right);
            edit.VerticalAlignment = VerticalAlignment.Center;
            edit.Click += delegate { ChooseProfileAvatar(); };
            account.Children.Add(edit);
            var name = StatText("我的阅读", 24, false, true);
            name.VerticalAlignment = VerticalAlignment.Center;
            account.Children.Add(name);
            _profileBody.Children.Add(account);

            DateTime today = DateTime.Today;
            DateTime monday = today.AddDays(-(((int)today.DayOfWeek + 6) % 7));
            DateTime[] days = Enumerable.Range(-6, 7).Select(offset => today.AddDays(offset)).ToArray();
            string first = TranslationUsage.Day(days[0]);
            string mondayKey = TranslationUsage.Day(monday);
            string last = TranslationUsage.Day(today);
            var week = rows.Where(row => string.CompareOrdinal(row.Day, first) >= 0 && string.CompareOrdinal(row.Day, last) <= 0).ToList();
            var scope = all ? rows : selected.HasValue ? week.Where(row => row.Day == TranslationUsage.Day(selected.Value)).ToList() : week;
            var chart = new StackPanel();
            chart.Children.Add(StatText("阅读时长", 17, false, true));
            var totals = new DockPanel { Margin = new Thickness(0, 8, 0, 2) };
            var summary = new StackPanel { VerticalAlignment = VerticalAlignment.Bottom };
            summary.Children.Add(StatText("今日 " + FormatDuration(week.Where(row => row.Day == last).Sum(row => row.Milliseconds)), 13, true));
            summary.Children.Add(StatText("本周 " + FormatDuration(week.Where(row => string.CompareOrdinal(row.Day, mondayKey) >= 0).Sum(row => row.Milliseconds)), 13, true));
            DockPanel.SetDock(summary, Dock.Right);
            totals.Children.Add(summary);
            var metric = new StackPanel();
            metric.Children.Add(StatText(all ? "累计" : selected.HasValue ? selected.Value.ToString("M 月 d 日") : "近 7 天", 13, true));
            metric.Children.Add(StatText(FormatDuration(scope.Sum(row => row.Milliseconds)), 36, false, true));
            totals.Children.Add(metric);
            chart.Children.Add(totals);
            chart.Children.Add(WeekBars(days, days.Select(day => week.Where(row => row.Day == TranslationUsage.Day(day)).Sum(row => row.Milliseconds)).ToArray(),
                selected, FormatDuration, day => RenderReadingProfile(rows, day == selected ? (DateTime?)null : day, false)));
            _profileBody.Children.Add(StatPanel(chart));

            var header = new DockPanel { Margin = new Thickness(0, 18, 0, 6) };
            var filters = new StackPanel { Orientation = Orientation.Horizontal };
            DockPanel.SetDock(filters, Dock.Right);
            foreach (bool entire in new[] { false, true })
            {
                bool filter = entire;
                var control = UiTheme.MakeButton(filter ? "全部" : "近 7 天", !selected.HasValue && all == filter, false);
                control.Click += delegate { RenderReadingProfile(rows, null, filter); };
                filters.Children.Add(control);
            }
            header.Children.Add(filters);
            var listTitle = StatText("作品明细", 18, false, true);
            listTitle.VerticalAlignment = VerticalAlignment.Center;
            header.Children.Add(listTitle);
            _profileBody.Children.Add(header);
            if (scope.Count == 0)
                _profileBody.Children.Add(StatText("这段时间没有阅读记录。", 14, true));
            foreach (var work in scope.GroupBy(row => row.ComicId).OrderByDescending(group => group.Sum(row => row.Milliseconds)))
            {
                var row = new DockPanel { Margin = new Thickness(2, 0, 2, 0), MinHeight = 43 };
                var total = StatText(FormatDuration(work.Sum(part => part.Milliseconds)), 14, true);
                total.VerticalAlignment = VerticalAlignment.Center;
                total.Margin = new Thickness(12, 0, 0, 0);
                DockPanel.SetDock(total, Dock.Right);
                row.Children.Add(total);
                var title = StatText(work.OrderByDescending(part => part.Day).First().Title, 15, false, true);
                title.VerticalAlignment = VerticalAlignment.Center;
                row.Children.Add(title);
                var separator = new Border { BorderThickness = new Thickness(0, 0, 0, 1), Child = row };
                separator.SetResourceReference(Border.BorderBrushProperty, "Brush.Border");
                _profileBody.Children.Add(separator);
            }
            Button feedback = AddBrowseButton(_profileBody, "问题反馈", OpenIssueFeedback);
            feedback.HorizontalAlignment = HorizontalAlignment.Left;
            feedback.Margin = new Thickness(0, 18, 0, 0);
        }

        private void OpenTranslationUsage()
        {
            var rows = TranslationUsage.Recent(_services.DataDir);
            var total = TranslationUsage.Sum(rows);
            var dialog = CreateStyledDialog("翻译 Token 用量", 555, 525);
            var content = new StackPanel { Margin = new Thickness(22) };
            content.Children.Add(StatText(total.Reported == 0 ? "暂无已报告用量" : total.Total.ToString("N0") + " Token", 28, false, true));
            content.Children.Add(StatText("最近 7 天 · " + total.Requests + " 次直连请求", 13, true));
            DateTime[] days = Enumerable.Range(-6, 7).Select(day => DateTime.Today.AddDays(day)).ToArray();
            content.Children.Add(WeekBars(days, days.Select(day => rows.Where(row => row.Day == TranslationUsage.Day(day)).Sum(row => row.Tokens.Total)).ToArray(),
                null, value => value.ToString("N0") + " Token"));
            var details = new StackPanel { Margin = new Thickness(0, 14, 0, 0) };
            if (total.Reported > 0)
            {
                details.Children.Add(StatText("输入 " + total.Input.ToString("N0") + "   输出 " + total.Output.ToString("N0"), 15));
                details.Children.Add(StatText("输出中含思考 " + total.Reasoning.ToString("N0"), 13, true));
            }
            if (total.Requests > total.Reported)
                details.Children.Add(StatText((total.Requests - total.Reported) + " 次请求未报告用量", 13, true));
            foreach (var model in rows.GroupBy(row => row.Model).OrderByDescending(group => group.Sum(row => row.Tokens.Total)))
            {
                var tokens = TranslationUsage.Sum(model);
                var title = StatText(model.Key, 15, false, true);
                title.Margin = new Thickness(0, 14, 0, 0);
                details.Children.Add(title);
                string amount = tokens.Reported == 0 ? "未报告 Token" : tokens.Total.ToString("N0") + " Token";
                details.Children.Add(StatText(amount + " · " + tokens.Requests + " 次请求", 13, true));
            }
            content.Children.Add(details);
            dialog.Content = new ScrollViewer { Content = content, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
            dialog.ShowDialog();
        }
    }
}
