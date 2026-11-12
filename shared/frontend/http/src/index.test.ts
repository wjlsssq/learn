import { afterEach, describe, expect, it, vi } from 'vitest'
import { createHttpClient, HttpClientError } from './index'

afterEach(() => vi.useRealTimers())

describe('HTTP transport boundaries', () => {
  it('joins a project base URL and preserves project headers and plain JSON', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(Response.json({ name: 'foundation' }))
    const client = createHttpClient({
      baseURL: '/foundation-api/',
      headers: { 'X-Client': 'portal' },
      fetch: fetcher,
    })
    await expect(
      client.getJson('/api/foundation/info', { headers: { 'X-Request': 'manual' } }),
    ).resolves.toEqual({ name: 'foundation' })
    expect(fetcher.mock.calls[0]?.[0]).toBe('/foundation-api/api/foundation/info')
    const headers = new Headers(fetcher.mock.calls[0]?.[1]?.headers)
    expect(headers.get('X-Client')).toBe('portal')
    expect(headers.get('X-Request')).toBe('manual')
    expect(headers.get('Accept')).toBe('application/json')
  })

  it('preserves HTTP status, error code, and correlation ID', async () => {
    const client = createHttpClient({
      fetch: vi
        .fn<typeof fetch>()
        .mockResolvedValue(
          Response.json(
            { code: 'INVALID_ARGUMENT', message: '参数不合法', traceId: 'request-42' },
            { status: 400 },
          ),
        ),
    })
    await expect(client.getJson('/invalid')).rejects.toMatchObject({
      kind: 'http',
      status: 400,
      code: 'INVALID_ARGUMENT',
      traceId: 'request-42',
      message: '参数不合法',
    })
  })

  it('never exposes HTML gateway error bodies', async () => {
    const client = createHttpClient({
      fetch: vi.fn<typeof fetch>().mockResolvedValue(
        new Response('<html>internal host secret</html>', {
          status: 502,
          headers: { 'X-Request-Id': 'proxy-1' },
        }),
      ),
    })
    await expect(client.getJson('/down')).rejects.toMatchObject({
      status: 502,
      traceId: 'proxy-1',
      message: '请求失败（HTTP 502）',
    })
  })

  it('supports an unchanged download or streaming response', async () => {
    const response = new Response('stream body', {
      headers: { 'Content-Type': 'text/event-stream' },
    })
    const client = createHttpClient({ fetch: vi.fn<typeof fetch>().mockResolvedValue(response) })
    const result = await client.request('/events')
    expect(result).toBe(response)
    expect(result.bodyUsed).toBe(false)
    expect(await result.text()).toBe('stream body')
  })

  it('keeps caller cancellation connected after streaming response headers have arrived', async () => {
    const controller = new AbortController()
    const fetcher = vi.fn<typeof fetch>().mockImplementation(async (_url, options) => {
      const stream = new ReadableStream({
        start(streamController) {
          streamController.enqueue(new TextEncoder().encode('first event'))
          options?.signal?.addEventListener(
            'abort',
            () => streamController.error(new DOMException('Aborted', 'AbortError')),
            { once: true },
          )
        },
      })
      return new Response(stream, { headers: { 'Content-Type': 'text/event-stream' } })
    })
    const response = await createHttpClient({ fetch: fetcher }).request('/events', {
      signal: controller.signal,
    })
    const reading = response.text()
    controller.abort()
    await expect(reading).rejects.toMatchObject({ name: 'AbortError' })
  })

  it('distinguishes invalid success JSON from network failures', async () => {
    const client = createHttpClient({
      fetch: vi.fn<typeof fetch>().mockResolvedValue(new Response('invalid', { status: 200 })),
    })
    await expect(client.getJson('/bad-json')).rejects.toMatchObject({
      kind: 'invalid-response',
      status: 200,
    })
    const offline = createHttpClient({
      fetch: vi.fn<typeof fetch>().mockRejectedValue(new TypeError('connection refused')),
    })
    await expect(offline.getJson('/offline')).rejects.toMatchObject({ kind: 'network' })
  })

  it('does not send requests which the caller has already cancelled', async () => {
    const fetcher = vi.fn<typeof fetch>()
    const controller = new AbortController()
    controller.abort()
    const client = createHttpClient({ fetch: fetcher })
    await expect(client.getJson('/cancelled', { signal: controller.signal })).rejects.toMatchObject(
      { kind: 'cancelled' },
    )
    expect(fetcher).not.toHaveBeenCalled()
  })

  it('aborts a hanging request when its deadline expires', async () => {
    vi.useFakeTimers()
    const fetcher = vi.fn<typeof fetch>().mockImplementation(
      (_url, options) =>
        new Promise((_resolve, reject) => {
          options?.signal?.addEventListener(
            'abort',
            () => reject(new DOMException('Aborted', 'AbortError')),
            { once: true },
          )
        }),
    )
    const pending = createHttpClient({ fetch: fetcher, timeoutMs: 50 }).getJson('/hanging')
    const assertion = expect(pending).rejects.toMatchObject({ kind: 'timeout' })
    await vi.advanceTimersByTimeAsync(51)
    await assertion
    expect(fetcher.mock.calls[0]?.[1]?.signal?.aborted).toBe(true)
  })

  it('propagates caller cancellation independently of a timeout', async () => {
    const controller = new AbortController()
    const fetcher = vi.fn<typeof fetch>().mockImplementation(
      (_url, options) =>
        new Promise((_resolve, reject) => {
          options?.signal?.addEventListener(
            'abort',
            () => reject(new DOMException('Aborted', 'AbortError')),
            { once: true },
          )
        }),
    )
    const pending = createHttpClient({ fetch: fetcher }).getJson('/cancel', {
      signal: controller.signal,
    })
    controller.abort()
    await expect(pending).rejects.toBeInstanceOf(HttpClientError)
    await expect(pending).rejects.toMatchObject({ kind: 'cancelled' })
  })
})
