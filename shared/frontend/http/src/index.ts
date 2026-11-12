export type HttpFailureKind = 'http' | 'network' | 'timeout' | 'cancelled' | 'invalid-response'

/** Transport failures keep their HTTP status and correlation ID for callers. */
export class HttpClientError extends Error {
  readonly kind: HttpFailureKind
  readonly status: number | undefined
  readonly code: string | undefined
  readonly traceId: string | undefined

  constructor(
    message: string,
    details: {
      kind: HttpFailureKind
      status?: number
      code?: string
      traceId?: string
      cause?: unknown
    },
  ) {
    super(message, { cause: details.cause })
    this.name = 'HttpClientError'
    this.kind = details.kind
    this.status = details.status
    this.code = details.code
    this.traceId = details.traceId
  }
}

export interface HttpClientOptions {
  baseURL?: string
  timeoutMs?: number
  headers?: HeadersInit
  fetch?: typeof globalThis.fetch
}

export interface HttpRequestOptions extends RequestInit {
  timeoutMs?: number
}

export interface HttpClient {
  /** Returns the original response. Deadline covers response headers; caller signal also cancels its stream. */
  request(path: string, options?: HttpRequestOptions): Promise<Response>
  /** Parses JSON without requiring a project-specific success envelope. */
  getJson<T>(path: string, options?: HttpRequestOptions): Promise<T>
}

function textField(value: unknown, field: string): string | undefined {
  if (typeof value !== 'object' || value === null || !(field in value)) return undefined
  const candidate = (value as Record<string, unknown>)[field]
  return typeof candidate === 'string' ? candidate : undefined
}

function joinURL(baseURL: string, path: string): string {
  if (/^https?:\/\//i.test(path)) return path
  return `${baseURL.replace(/\/$/, '')}/${path.replace(/^\//, '')}`
}

export function createHttpClient(config: HttpClientOptions = {}): HttpClient {
  const fetcher = config.fetch ?? globalThis.fetch.bind(globalThis)
  const baseURL = config.baseURL ?? ''
  const defaultTimeout = config.timeoutMs ?? 8_000

  async function perform<T>(
    path: string,
    options: HttpRequestOptions,
    consume: (response: Response) => Promise<T>,
  ): Promise<T> {
    const { timeoutMs = defaultTimeout, signal, headers: requestHeaders, ...requestInit } = options
    if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) {
      throw new RangeError('timeoutMs must be a positive finite number')
    }
    const controller = new AbortController()
    let timedOut = false
    // The composite signal remains attached to fetch after returning a Response.
    // Removing a forwarding listener here would break download/SSE cancellation.
    const requestSignal = signal ? AbortSignal.any([controller.signal, signal]) : controller.signal
    const timer = setTimeout(() => {
      timedOut = true
      controller.abort()
    }, timeoutMs)
    const headers = new Headers(config.headers)
    new Headers(requestHeaders).forEach((value, key) => headers.set(key, value))

    try {
      if (requestSignal.aborted) throw new DOMException('Request cancelled', 'AbortError')
      const response = await fetcher(joinURL(baseURL, path), {
        ...requestInit,
        headers,
        signal: requestSignal,
      })
      if (!response.ok) {
        let payload: unknown
        if (response.headers.get('content-type')?.includes('json')) {
          try {
            payload = await response.json()
          } catch {
            /* Non-JSON error bodies use the safe fallback. */
          }
        }
        throw new HttpClientError(
          textField(payload, 'message') ?? `请求失败（HTTP ${response.status}）`,
          {
            kind: 'http',
            status: response.status,
            code: textField(payload, 'code'),
            traceId:
              textField(payload, 'traceId') ?? response.headers.get('X-Request-Id') ?? undefined,
          },
        )
      }
      return await consume(response)
    } catch (error) {
      if (timedOut)
        throw new HttpClientError('请求超时，请确认服务状态', { kind: 'timeout', cause: error })
      if (signal?.aborted)
        throw new HttpClientError('请求已取消', { kind: 'cancelled', cause: error })
      if (error instanceof HttpClientError) throw error
      throw new HttpClientError('无法连接服务，请检查启动状态与网络', {
        kind: 'network',
        cause: error,
      })
    } finally {
      clearTimeout(timer)
    }
  }

  return {
    request: (path, options = {}) => perform(path, options, async (response) => response),
    getJson: <T>(path: string, options: HttpRequestOptions = {}) => {
      const headers = new Headers(options.headers)
      if (!headers.has('Accept')) headers.set('Accept', 'application/json')
      return perform<T>(path, { ...options, headers }, async (response) => {
        try {
          return (await response.json()) as T
        } catch (cause) {
          throw new HttpClientError('服务返回了无法解析的 JSON', {
            kind: 'invalid-response',
            status: response.status,
            traceId: response.headers.get('X-Request-Id') ?? undefined,
            cause,
          })
        }
      })
    },
  }
}
