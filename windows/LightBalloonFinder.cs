using System;
using System.Collections.Generic;
using System.Windows;

namespace KomaComic.Windows
{
    internal sealed class LightBalloonFinder
    {
        internal sealed class Balloon
        {
            public int Id;
            public Int32Rect Bounds;
            public Int32Rect TextArea;
            public bool[] Safe;
            public int Background;
        }

        private sealed class Component
        {
            public int Count, MinX, MinY, MaxX, MaxY, Red, Green, Blue;
        }

        private readonly int _width, _height;
        private readonly int[] _pixels, _labels, _queue;
        private readonly bool[] _open;
        private readonly List<Component> _components = new List<Component> { null };

        public LightBalloonFinder(int width, int height, int[] pixels)
        {
            _width = width;
            _height = height;
            _pixels = pixels;
            _labels = new int[pixels.Length];
            _queue = new int[pixels.Length];
            _open = new bool[pixels.Length];
            var light = new bool[pixels.Length];
            for (int i = 0; i < pixels.Length; i++) light[i] = Brightness(pixels[i]) >= 232;
            // Shrink the light component slightly so tiny gaps in an ink outline do not connect to the artwork.
            for (int y = 1; y < height - 1; y++)
                for (int x = 1, i = y * width + 1; x < width - 1; x++, i++)
                    _open[i] = light[i] && light[i - 1] && light[i + 1] && light[i - width] && light[i + width];
        }

        public static int Brightness(int pixel)
        {
            return Math.Min((pixel >> 16) & 255, Math.Min((pixel >> 8) & 255, pixel & 255));
        }

        public Balloon Find(Rect normalizedBox)
        {
            var scores = new Dictionary<int, int>();
            for (int row = 0; row < 9; row++)
                for (int column = 0; column < 9; column++)
                {
                    int x = Math.Min(_width - 1, (int)((normalizedBox.Left + normalizedBox.Width * (column + 0.5) / 9) * _width / 1000));
                    int y = Math.Min(_height - 1, (int)((normalizedBox.Top + normalizedBox.Height * (row + 0.5) / 9) * _height / 1000));
                    int index = y * _width + x;
                    if (!_open[index]) continue;
                    int id = _labels[index] == 0 ? Label(index) : _labels[index];
                    int votes;
                    scores.TryGetValue(id, out votes);
                    scores[id] = votes + ((row >= 2 && row <= 6 && column >= 2 && column <= 6) ? 3 : 1);
                }

            int chosen = 0, best = 13;
            foreach (var entry in scores)
            {
                Component c = _components[entry.Key];
                int w = c.MaxX - c.MinX + 1, h = c.MaxY - c.MinY + 1;
                if (entry.Value <= best || w < 12 || h < 12 ||
                    w > _width * 0.9 || h > _height * 0.92 ||
                    w * h > _labels.Length * 0.62 || c.Count < w * h * 0.37) continue;
                best = entry.Value;
                chosen = entry.Key;
            }
            return chosen == 0 ? null : BuildBalloon(chosen);
        }

        private int Label(int start)
        {
            int id = _components.Count;
            var component = new Component { MinX = _width, MinY = _height };
            _components.Add(component);
            int end = 0;
            Enqueue(start, id, ref end);
            for (int head = 0; head < end; head++)
            {
                int pixel = _queue[head];
                int x = pixel % _width, y = pixel / _width;
                component.MinX = Math.Min(x, component.MinX);
                component.MinY = Math.Min(y, component.MinY);
                component.MaxX = Math.Max(x, component.MaxX);
                component.MaxY = Math.Max(y, component.MaxY);
                int color = _pixels[pixel];
                component.Red += (color >> 16) & 255;
                component.Green += (color >> 8) & 255;
                component.Blue += color & 255;
                Enqueue(pixel - 1, id, ref end);
                Enqueue(pixel + 1, id, ref end);
                Enqueue(pixel - _width, id, ref end);
                Enqueue(pixel + _width, id, ref end);
            }
            component.Count = end;
            return id;
        }

        private void Enqueue(int pixel, int id, ref int end)
        {
            if (!_open[pixel] || _labels[pixel] != 0) return;
            _labels[pixel] = id;
            _queue[end++] = pixel;
        }

        private Balloon BuildBalloon(int id)
        {
            Component component = _components[id];
            int w = component.MaxX - component.MinX + 1, h = component.MaxY - component.MinY + 1;
            int stride = w + 2, rows = h + 2;
            int[] distance = new int[stride * rows];
            bool[] solid = new bool[distance.Length];
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++)
                    solid[(y + 1) * stride + x + 1] = _labels[(component.MinY + y) * _width + component.MinX + x] == id;

            // Flood the complement from the outside. Enclosed holes are ink inside the balloon, not its outline.
            var scan = new int[distance.Length];
            int end = 1;
            distance[0] = -1;
            for (int head = 0; head < end; head++)
            {
                int p = scan[head], x = p % stride, y = p / stride;
                if (x > 0) ScanOutside(p - 1, solid, distance, scan, ref end);
                if (x < stride - 1) ScanOutside(p + 1, solid, distance, scan, ref end);
                if (y > 0) ScanOutside(p - stride, solid, distance, scan, ref end);
                if (y < rows - 1) ScanOutside(p + stride, solid, distance, scan, ref end);
            }
            for (int i = 0; i < distance.Length; i++) distance[i] = distance[i] == -1 ? 0 : w + h + 1;
            for (int y = 1; y <= h; y++)
                for (int x = 1, p = y * stride + 1; x <= w; x++, p++)
                    distance[p] = Math.Min(distance[p], 1 + Math.Min(distance[p - 1], distance[p - stride]));
            for (int y = h; y >= 1; y--)
                for (int x = w, p = y * stride + w; x >= 1; x--, p--)
                    distance[p] = Math.Min(distance[p], 1 + Math.Min(distance[p + 1], distance[p + stride]));

            var safe = new bool[w * h];
            int inset = Math.Max(2, Math.Min(w, h) / 55);
            int ink = 0, interior = 0;
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++)
                    if (distance[(y + 1) * stride + x + 1] > inset)
                    {
                        safe[y * w + x] = true;
                        interior++;
                        if (Brightness(_pixels[(component.MinY + y) * _width + component.MinX + x]) < 213) ink++;
                    }
            if (ink < 6 || ink > interior * 0.30) return null;
            Int32Rect area = LargestRectangle(safe, w, h);
            if (area.Width < 9 || area.Height < 9) return null;
            area.X += component.MinX;
            area.Y += component.MinY;
            int count = component.Count;
            int background = unchecked((int)0xff000000) | ((component.Red + count / 2) / count << 16) |
                ((component.Green + count / 2) / count << 8) | (component.Blue + count / 2) / count;
            return new Balloon { Id = id, Bounds = new Int32Rect(component.MinX, component.MinY, w, h),
                TextArea = area, Safe = safe, Background = background };
        }

        private static void ScanOutside(int p, bool[] solid, int[] distance, int[] scan, ref int end)
        {
            if (solid[p] || distance[p] != 0) return;
            distance[p] = -1;
            scan[end++] = p;
        }

        // Find the largest axis-aligned rectangle wholly inside the safe mask.
        private static Int32Rect LargestRectangle(bool[] mask, int w, int h)
        {
            var result = new Int32Rect();
            int[] heights = new int[w + 1];
            int[] stack = new int[w + 1];
            int largest = 0;
            for (int y = 0; y < h; y++)
            {
                int size = 0;
                for (int x = 0; x <= w; x++)
                {
                    heights[x] = x == w || !mask[y * w + x] ? 0 : heights[x] + 1;
                    while (size > 0 && heights[stack[size - 1]] >= heights[x])
                    {
                        int height = heights[stack[--size]];
                        int left = size == 0 ? 0 : stack[size - 1] + 1;
                        int area = height * (x - left);
                        if (area <= largest) continue;
                        largest = area;
                        result = new Int32Rect(left, y - height + 1, x - left, height);
                    }
                    stack[size++] = x;
                }
            }
            return result;
        }
    }
}
