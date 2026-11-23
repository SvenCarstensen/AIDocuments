import { createServer, type Server } from 'node:http'
import type { Analysis } from './samples.ts'

/**
 * Ein Schein-KI-Server mit OpenAI-Schnittstelle (Chat Completions) für die Screenshots: Er liefert für jedes
 * Beispieldokument die vorbereitete Erschließung, beantwortet die eine Beispielfrage im Dokument und übersetzt die
 * Beispielsuche. So sehen die Bilder aus wie mit echter KI, ohne dass ein Modell laufen muss.
 */
export interface MockAnswers {
  analyses: Map<string, Analysis>
  /** Ergebnis der KI-Suche (Werkzeug suche_ausfuehren) */
  search: Record<string, unknown>
  /** Antwort auf die Frage im Dokument */
  chat(question: string): string
}

export function startMockAi(port: number, answers: MockAnswers): Promise<Server> {
  const server = createServer((request, response) => {
    let body = ''
    request.on('data', (chunk: Buffer) => (body += chunk.toString()))
    request.on('end', () => {
      const send = (payload: unknown) => {
        response.writeHead(200, { 'Content-Type': 'application/json' })
        response.end(JSON.stringify(payload))
      }
      if (request.method === 'GET' && request.url?.endsWith('/models')) {
        send({ object: 'list', data: [{ id: 'local-model', object: 'model' }] })
        return
      }
      if (request.method !== 'POST' || !request.url?.endsWith('/chat/completions')) {
        response.writeHead(404).end()
        return
      }
      const chat = JSON.parse(body) as {
        messages: Array<{ role: string; content: string | Array<{ type: string; text?: string }> }>
        tools?: Array<{ function: { name: string } }>
      }
      const text = chat.messages
        .map((m) => (typeof m.content === 'string' ? m.content : m.content.map((p) => p.text ?? '').join('\n')))
        .join('\n')
      const tool = chat.tools?.[0]?.function.name
      const reply = (message: Record<string, unknown>) =>
        send({ id: 'mock', object: 'chat.completion', choices: [{ index: 0, finish_reason: 'stop', message: { role: 'assistant', ...message } }], usage: { completion_tokens: 200 } })
      const call = (name: string, args: unknown) =>
        reply({ content: null, tool_calls: [{ id: 'call_1', type: 'function', function: { name, arguments: JSON.stringify(args) } }] })

      if (tool === 'dokument_erschliessen') {
        const filename = /Dateiname: (.+)/.exec(text)?.[1]?.trim() ?? ''
        const analysis = answers.analyses.get(filename)
        if (!analysis) {
          response.writeHead(500).end(`kein Beispiel für ${filename}`)
          return
        }
        call(tool, { ...analysis, language: 'en', fullText: '' })
      } else if (tool) {
        call(tool, answers.search)
      } else {
        const last = chat.messages.at(-1)?.content
        reply({ content: answers.chat(typeof last === 'string' ? last : '') })
      }
    })
  })
  return new Promise((resolve) => server.listen(port, '127.0.0.1', () => resolve(server)))
}
