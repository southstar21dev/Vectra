/// <reference lib="webworker" />
import { parseICSContent } from './ics-parser';
import { getErrorMessage } from '@/lib/error-utils';

declare const self: DedicatedWorkerGlobalScope;

export interface ParseRequest {
  id: string;
  text: string;
  includeExams?: boolean;
}

export interface ParseResponse {
  id: string;
  result?: ReturnType<typeof parseICSContent>;
  error?: string;
}

self.onmessage = (event: MessageEvent<ParseRequest>) => {
  const { id, text, includeExams } = event.data;

  try {
    const result = parseICSContent(text, { includeExams });
    const response: ParseResponse = { id, result };
    self.postMessage(response);
  } catch (err) {
    const response: ParseResponse = { id, error: getErrorMessage(err, 'Failed to parse ICS content.') };
    self.postMessage(response);
  }
};


