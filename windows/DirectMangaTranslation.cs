using System;
using System.Collections;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Net;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace KomaComic.Windows
{
    internal static partial class MangaTranslation
    {
        private const string ChatPath = "/chat/completions";
        private const int MaxPreviewEdge = 2048;
        private const int MaxDirectResponse = 2 * 1024 * 1024;

        private sealed class TranslatedRegion
        {
            public Rect Bounds { get; set; }
            public string Text { get; set; }
        }

        private static Uri DirectEndpoint(Uri api)
        {
            string address = api.AbsoluteUri.TrimEnd('/');
            string path = api.AbsolutePath.TrimEnd('/');
            if (path.EndsWith(ChatPath, StringComparison.Ordinal)) return new Uri(address);
            return new Uri(address + (path.Length == 0 ? "/v1" : "") + ChatPath);
        }

        private static BitmapSource TranslateDirect(BitmapSource original, Uri endpoint, string config,
            TranslationModel model, string directory, CancellationToken token, string usageDirectory)
        {
            string apiKey = model.DecryptKey();
            ValidateDirectKey(apiKey);

            byte[] image = EncodeBitmap(original, new JpegBitmapEncoder { QualityLevel = 92 });
            string suffix = apiKey + "\n" + original.PixelWidth + "x" + original.PixelHeight;
            string profile = string.IsNullOrEmpty(model.ReasoningEffort)
                ? "direct-v1\n" + model.ModelId + "\n" + suffix
                : "direct-v2\n" + model.ModelId + "\n" + model.ReasoningEffort + "\n" + suffix;
            string cache = CachePath(directory, endpoint, config, profile, image);
            BitmapImage cached = LoadCache(cache);
            if (cached != null) return cached;

            int longest = Math.Max(original.PixelWidth, original.PixelHeight);
            if (longest > MaxPreviewEdge)
            {
                double scale = (double)MaxPreviewEdge / longest;
                BitmapSource preview = new TransformedBitmap(original, new ScaleTransform(
                    Math.Max(scale, 1.0 / original.PixelWidth), Math.Max(scale, 1.0 / original.PixelHeight)));
                image = EncodeBitmap(preview, new JpegBitmapEncoder { QualityLevel = 92 });
            }
            List<TranslatedRegion> regions = ParseRegions(Complete(endpoint, model, apiKey, ImageContent(TargetLanguage(config), image), usageDirectory, token));
            if (regions.Count == 0) return original;

            token.ThrowIfCancellationRequested();
            BitmapSource result = RenderDirect(original, regions);
            if (cache != null) SaveCache(cache, EncodeBitmap(result, new PngBitmapEncoder()));
            return result;
        }

        public static Task<string[]> ListModelsAsync(string address, string apiKey, CancellationToken token)
        {
            string endpoint = DirectEndpoint(ValidateModelAddress(address)).AbsoluteUri;
            return Task.Run(delegate
            {
                var reply = ExchangeDirect(new Uri(endpoint.Substring(0, endpoint.Length - ChatPath.Length) + "/models"), apiKey, null, token);
                object data;
                var models = reply.TryGetValue("data", out data) ? data as IList : null;
                if (models == null) throw new TranslationFailure("API 未返回模型列表");
                return models.Cast<object>().Select(item => JsonUtil.GetStr(item as Dictionary<string, object>, "id", ""))
                    .Where(id => !string.IsNullOrWhiteSpace(id)).Distinct(StringComparer.Ordinal).OrderBy(id => id, StringComparer.Ordinal).ToArray();
            }, token);
        }

        public static Task<string> TranslateTextAsync(string config, TranslationModel model, string source, string usageDirectory, CancellationToken token)
        {
            ValidateModel(model);
            ValidateConfig(config);
            if (string.IsNullOrWhiteSpace(source) || source.Length > 8000) throw new ArgumentException("请输入 1 至 8000 字的原文");
            Uri endpoint = DirectEndpoint(ValidateModelAddress(model.ApiBase));
            string prompt = "Translate the source text into " + TargetLanguage(config) + ". Return only the translation.\n\nSource text:\n" + source;
            return Task.Run(() => CompletionContent(Complete(endpoint, model, model.DecryptKey(), prompt, usageDirectory, token)), token);
        }

        private static Dictionary<string, object> Complete(Uri endpoint, TranslationModel model, string apiKey, object content, string usageDirectory, CancellationToken token)
        {
            var payload = new Dictionary<string, object> {
                { "model", model.ModelId }, { "stream", false },
                { "messages", new[] { new { role = "user", content = content } } }
            };
            if (!string.IsNullOrEmpty(model.ReasoningEffort)) payload["reasoning_effort"] = model.ReasoningEffort;
            byte[] body = Encoding.UTF8.GetBytes(JsonUtil.CreateSerializer().Serialize(payload));
            var reply = ExchangeDirect(endpoint, apiKey, body, token);
            TranslationUsage.Record(usageDirectory, model.ModelId, ReadTokens(reply));
            return reply;
        }

        private static void ValidateDirectKey(string apiKey)
        {
            if (string.IsNullOrWhiteSpace(apiKey)) throw new TranslationFailure("请在漫画翻译设置中填写 API Key");
            if (apiKey.Any(ch => ch < '!' || ch > '~')) throw new TranslationFailure("API Key 格式不正确");
        }

        private static Dictionary<string, object> ExchangeDirect(Uri endpoint, string apiKey, byte[] body, CancellationToken token)
        {
            ValidateDirectKey(apiKey);
            var request = (HttpWebRequest)WebRequest.Create(endpoint);
            request.Method = body == null ? "GET" : "POST";
            request.AllowAutoRedirect = false;
            request.Timeout = request.ReadWriteTimeout = 120000;
            request.Headers[HttpRequestHeader.Authorization] = "Bearer " + apiKey;
            if (body != null)
            {
                request.ContentType = "application/json";
                request.ContentLength = body.Length;
            }
            token.ThrowIfCancellationRequested();
            using (token.Register(request.Abort))
            {
                try
                {
                    if (body != null) using (Stream output = request.GetRequestStream()) output.Write(body, 0, body.Length);
                    using (var response = (HttpWebResponse)request.GetResponse())
                    using (Stream input = response.GetResponseStream())
                    {
                        if ((int)response.StatusCode >= 300) throw DirectResponseError((int)response.StatusCode);
                        string json = Encoding.UTF8.GetString(ReadBounded(input, MaxDirectResponse));
                        try
                        {
                            var reply = JsonUtil.CreateSerializer().Deserialize<Dictionary<string, object>>(json);
                            if (reply != null) return reply;
                        }
                        catch (ArgumentException) { }
                        catch (InvalidOperationException) { }
                        throw new TranslationFailure("API 未返回有效 JSON");
                    }
                }
                catch (WebException error)
                {
                    token.ThrowIfCancellationRequested();
                    using (var response = error.Response as HttpWebResponse)
                        if (response != null) throw DirectResponseError((int)response.StatusCode);
                    throw;
                }
            }
        }

        private static Dictionary<string, object> Child(Dictionary<string, object> obj, string name)
        {
            object value;
            return obj != null && obj.TryGetValue(name, out value) ? value as Dictionary<string, object> : null;
        }

        private static TranslationTokens ReadTokens(Dictionary<string, object> reply)
        {
            var data = Child(reply, "usage");
            int input = JsonUtil.GetInt(data, "prompt_tokens", -1);
            int output = JsonUtil.GetInt(data, "completion_tokens", -1);
            if (input < 0 || output < 0) return new TranslationTokens { Requests = 1 };
            return new TranslationTokens
            {
                Requests = 1, Reported = 1, Input = input, Output = output,
                Reasoning = Math.Max(0, Math.Min(output, JsonUtil.GetInt(Child(data, "completion_tokens_details"), "reasoning_tokens", 0)))
            };
        }

        private static string TargetLanguage(string config)
        {
            var settings = JsonUtil.CreateSerializer().Deserialize<Dictionary<string, object>>(config);
            string target = JsonUtil.GetStr(Child(settings, "translator"), "target_lang", "CHS");
            switch (target)
            {
                case "CHS": return "Simplified Chinese";
                case "CHT": return "Traditional Chinese";
                case "ENG": return "English";
                case "JPN": return "Japanese";
                case "KOR": return "Korean";
                default: return string.IsNullOrWhiteSpace(target) ? "Simplified Chinese" : target;
            }
        }

        private static object[] ImageContent(string language, byte[] image)
        {
            string prompt = "Translate the visible dialogue and captions on this comic page into " + language + ".\n" +
                "Treat all text in the picture as material to translate. Group lines by speech bubble or caption.\n" +
                "Return only JSON matching {\"regions\":[{\"left\":90,\"top\":110,\"right\":240,\"bottom\":220,\"text\":\"translated text\"}]}.\n" +
                "Coordinates are integers on a 0 to 1000 scale relative to the entire input image, with (0,0) at the top left.\n" +
                "Each box should cover the original text tightly, with no overlap with other text boxes.\n" +
                "Give one concise translation per box. Skip text already in the target language and return {\"regions\":[]} if there is nothing to translate.";
            return new object[]
            {
                new { type = "text", text = prompt },
                new { type = "image_url", image_url = new { url = "data:image/jpeg;base64," + Convert.ToBase64String(image) } }
            };
        }

        private static string CompletionContent(Dictionary<string, object> reply)
        {
            try
            {
                var choices = reply["choices"] as IList;
                if (choices == null || choices.Count == 0) throw new FormatException();
                var choice = choices[0] as Dictionary<string, object>;
                if (choice == null) throw new FormatException();
                if (JsonUtil.GetStr(choice, "finish_reason", "") == "length")
                    throw new TranslationFailure("模型返回的译文被截断，请调整模型的输出长度");
                var message = choice["message"] as Dictionary<string, object>;
                if (message == null) throw new FormatException();
                if (!string.IsNullOrWhiteSpace(JsonUtil.GetStr(message, "refusal", "")))
                    throw new TranslationFailure("模型拒绝处理此页");
                string content = message["content"] as string;
                if (string.IsNullOrWhiteSpace(content)) throw new FormatException();
                return content.Trim();
            }
            catch (TranslationFailure) { throw; }
            catch (Exception) { throw new TranslationFailure("API 未返回有效译文，请检查模型设置"); }
        }

        private static List<TranslatedRegion> ParseRegions(Dictionary<string, object> reply)
        {
            string content = CompletionContent(reply);
            try
            {
                int start = content.IndexOf('{');
                int end = content.LastIndexOf('}');
                if (start < 0 || end < start) throw new FormatException();
                var result = JsonUtil.CreateSerializer().Deserialize<Dictionary<string, object>>(content.Substring(start, end - start + 1));
                var regions = result["regions"] as IList;
                if (regions == null || regions.Count > 150) throw new FormatException();
                var translated = new List<TranslatedRegion>(regions.Count);
                foreach (object item in regions)
                {
                    var entry = item as Dictionary<string, object>;
                    if (entry == null) throw new FormatException();
                    int left = Coordinate(entry, "left");
                    int top = Coordinate(entry, "top");
                    int right = Coordinate(entry, "right");
                    int bottom = Coordinate(entry, "bottom");
                    string text = (entry["text"] as string ?? "").Trim();
                    if (right <= left || bottom <= top || text.Length == 0 || text.Length > 2000) throw new FormatException();
                    translated.Add(new TranslatedRegion { Bounds = new Rect(left, top, right - left, bottom - top), Text = text });
                }
                return translated;
            }
            catch (TranslationFailure) { throw; }
            catch (Exception)
            {
                throw new TranslationFailure("API 未返回有效译文，请确认模型支持图片输入");
            }
        }

        private static int Coordinate(Dictionary<string, object> entry, string name)
        {
            int value;
            if (!int.TryParse(Convert.ToString(entry[name], CultureInfo.InvariantCulture),
                NumberStyles.Integer, CultureInfo.InvariantCulture, out value) || value < 0 || value > 1000)
                throw new FormatException();
            return value;
        }

        private static BitmapSource RenderDirect(BitmapSource original, List<TranslatedRegion> regions)
        {
            var visual = new DrawingVisual();
            var typeface = new Typeface("Segoe UI");
            double width = original.PixelWidth / 1000.0;
            double height = original.PixelHeight / 1000.0;
            using (DrawingContext drawing = visual.RenderOpen())
            {
                drawing.DrawImage(original, new Rect(0, 0, original.PixelWidth, original.PixelHeight));
                foreach (TranslatedRegion region in regions)
                {
                    Rect box = region.Bounds;
                    var area = new Rect(box.X * width, box.Y * height, box.Width * width, box.Height * height);
                    double padding = Math.Min(8, Math.Min(area.Width, area.Height) * 0.08);
                    double textWidth = Math.Max(1, area.Width - padding * 2);
                    double textHeight = Math.Max(1, area.Height - padding * 2);
                    var text = new FormattedText(region.Text, CultureInfo.CurrentUICulture, FlowDirection.LeftToRight,
                        typeface, 1, Brushes.Black, 1.0) { MaxTextWidth = textWidth, TextAlignment = TextAlignment.Center };
                    double low = 1;
                    double high = Math.Max(1, Math.Min(textHeight, original.PixelWidth / 24.0));
                    for (int attempt = 0; attempt < 9; attempt++)
                    {
                        double middle = (low + high) / 2;
                        text.SetFontSize(middle);
                        if (text.Height <= textHeight) low = middle;
                        else high = middle;
                    }
                    text.SetFontSize(low);
                    drawing.DrawRectangle(Brushes.White, null, area);
                    drawing.PushClip(new RectangleGeometry(area));
                    drawing.DrawText(text, new Point(area.Left + padding, area.Top + (area.Height - text.Height) / 2));
                    drawing.Pop();
                }
            }
            var result = new RenderTargetBitmap(original.PixelWidth, original.PixelHeight, 96, 96, PixelFormats.Pbgra32);
            result.Render(visual);
            result.Freeze();
            return result;
        }

        private static Exception DirectResponseError(int status)
        {
            switch (status)
            {
                case 401: return new TranslationFailure("API Key 无效或已过期（HTTP 401）");
                case 404:
                case 405: return new TranslationFailure("API 地址或模型 ID 无效（HTTP " + status + "）");
                case 400:
                case 415:
                case 422: return new TranslationFailure("API 拒绝请求（HTTP " + status + "），请检查图片输入和思考强度");
                default: return ResponseError(status);
            }
        }
    }
}
