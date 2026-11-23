using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Text;
using System.Threading.Tasks;

namespace KomaComic.Windows
{
    internal static class GithubFeedback
    {
        private static readonly Uri Issues = new Uri("https://api.github.com/repos/zmf678/koma-comic/issues");

        public static Task<int> SubmitAsync(string title, string body, string token, Uri endpoint = null)
        {
            if (string.IsNullOrEmpty(token) || token.Any(ch => ch < '!' || ch > '~'))
                throw new ArgumentException("请填写有效的 GitHub Token");
            byte[] payload = Encoding.UTF8.GetBytes(JsonUtil.CreateSerializer().Serialize(new { title = title, body = body }));
            return Task.Run(delegate
            {
                var request = (HttpWebRequest)WebRequest.Create(endpoint ?? Issues);
                request.Method = "POST";
                request.AllowAutoRedirect = false;
                request.Timeout = request.ReadWriteTimeout = 20000;
                request.Accept = "application/vnd.github+json";
                request.UserAgent = "KomaComic";
                request.ContentType = "application/json; charset=utf-8";
                request.Headers[HttpRequestHeader.Authorization] = "Bearer " + token;
                request.Headers["X-GitHub-Api-Version"] = "2026-03-10";
                request.ContentLength = payload.Length;
                try
                {
                    using (Stream stream = request.GetRequestStream()) stream.Write(payload, 0, payload.Length);
                    using (var response = (HttpWebResponse)request.GetResponse())
                    {
                        if (response.StatusCode != HttpStatusCode.Created) throw StatusError((int)response.StatusCode);
                        try
                        {
                            using (var reader = new StreamReader(response.GetResponseStream()))
                            {
                                var buffer = new char[128 * 1024];
                                int length = reader.ReadBlock(buffer, 0, buffer.Length);
                                var item = JsonUtil.CreateSerializer().Deserialize<Dictionary<string, object>>(new string(buffer, 0, length));
                                return Math.Max(0, JsonUtil.GetInt(item, "number", 0));
                            }
                        }
                        catch (Exception) { return 0; }
                    }
                }
                catch (WebException error)
                {
                    using (var response = error.Response as HttpWebResponse)
                        if (response != null) throw StatusError((int)response.StatusCode);
                    throw new InvalidOperationException("连接 GitHub 失败，请核对 Issue 列表再重试");
                }
            });
        }

        private static Exception StatusError(int status)
        {
            switch (status)
            {
                case 401: return new InvalidOperationException("GitHub Token 无效或已过期");
                case 403:
                case 404: return new InvalidOperationException("GitHub 拒绝提交，请检查 Token 能否向该仓库创建 Issue");
                case 410: return new InvalidOperationException("该仓库已关闭 Issues");
                case 422: return new InvalidOperationException("GitHub 拒绝此反馈，请检查标题和内容");
                case 429: return new InvalidOperationException("GitHub 请求过于频繁，请稍后提交");
                default: return new InvalidOperationException("GitHub 提交失败（HTTP " + status + "）");
            }
        }
    }
}
