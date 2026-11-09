import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { ChatStore } from './chat-store';
import { ChatApiService } from '../core/chat-api.service';
import { ChatResponse } from '../core/models';

describe('ChatStore', () => {
  let store: ChatStore;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        ChatStore,
        ChatApiService,
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    store = TestBed.inject(ChatStore);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('greet() adds a single agent message', () => {
    store.greet('Ana');
    expect(store.messages().length).toBe(1);
    expect(store.messages()[0].from).toBe('agent');
    expect(store.messages()[0].text).toContain('Ana');
  });

  it('send() adds the user message, blocks sending and appends the agent reply', () => {
    store.send('¿dónde está mi pedido?');

    expect(store.sending()).toBeTrue();
    expect(store.messages().length).toBe(1);
    expect(store.messages()[0]).toEqual(jasmine.objectContaining({
      from: 'user',
      text: '¿dónde está mi pedido?',
    }));

    const req = httpMock.expectOne('/api/copilot/chat');
    expect(req.request.method).toBe('POST');
    expect(req.request.body.message).toBe('¿dónde está mi pedido?');
    expect(req.request.body.conversationId).toBe(store.conversationId);

    const fake: ChatResponse = {
      conversationId: store.conversationId,
      reply: 'Tu pedido está en reparto.',
      intent: 'TRACKING',
      resolved: true,
    };
    req.flush(fake);

    expect(store.sending()).toBeFalse();
    expect(store.messages().length).toBe(2);
    expect(store.messages()[1]).toEqual(jasmine.objectContaining({
      from: 'agent',
      text: 'Tu pedido está en reparto.',
      intent: 'TRACKING',
    }));
  });

  it('send() on error shows a fallback message and releases the lock', () => {
    store.send('hola');
    const req = httpMock.expectOne('/api/copilot/chat');
    req.error(new ProgressEvent('error'), { status: 500, statusText: 'Server Error' });

    expect(store.sending()).toBeFalse();
    expect(store.messages().length).toBe(2);
    expect(store.messages()[1].from).toBe('agent');
    expect(store.messages()[1].text).toContain('no puedo atenderte');
  });

  it('send() with displayText shows the label but sends the button payload', () => {
    store.send('action:confirm-cancel:123', 'Sí, cancelar el pedido');

    expect(store.messages()[0]).toEqual(jasmine.objectContaining({
      from: 'user',
      text: 'Sí, cancelar el pedido',
    }));

    const req = httpMock.expectOne('/api/copilot/chat');
    expect(req.request.body.message).toBe('action:confirm-cancel:123');
    req.flush({
      conversationId: store.conversationId,
      reply: 'Pedido cancelado.',
      intent: 'CANCEL',
      resolved: true,
    } as ChatResponse);
  });

  it('send() removes pending confirmation buttons from earlier messages', () => {
    store.send('cancela el 123');
    httpMock.expectOne('/api/copilot/chat').flush({
      conversationId: store.conversationId,
      reply: '¿Confirmas?',
      intent: 'CANCEL',
      resolved: false,
      confirm: {
        orderId: 123,
        title: '¿Confirmas la cancelación del pedido #123?',
        warning: 'Esta acción no se puede deshacer.',
        confirmLabel: 'Sí, cancelar el pedido',
        confirmPayload: 'action:confirm-cancel:123',
        cancelLabel: 'No, conservarlo',
        cancelPayload: 'action:abort-cancel:123',
      },
    } as ChatResponse);

    expect(store.messages()[1].confirm).toBeTruthy();

    store.send('action:confirm-cancel:123', 'Sí, cancelar el pedido');
    expect(store.messages()[1].confirm).toBeFalsy();
    httpMock.expectOne('/api/copilot/chat').flush({
      conversationId: store.conversationId, reply: 'Hecho.', intent: 'CANCEL', resolved: true,
    } as ChatResponse);
  });

  it('send() ignores empty text', () => {
    store.send('   ');
    expect(store.messages().length).toBe(0);
    expect(store.sending()).toBeFalse();
    httpMock.expectNone('/api/copilot/chat');
  });

  it('newConversation() regenerates the threadId and clears the history except the greeting', () => {
    const original = store.conversationId;
    store.send('hola');
    httpMock.expectOne('/api/copilot/chat').flush({
      conversationId: original,
      reply: '¡Hola!',
      intent: 'OTHER',
      resolved: false,
    } as ChatResponse);

    store.newConversation('Ana');
    expect(store.conversationId).not.toBe(original);
    expect(store.messages().length).toBe(1);
    expect(store.messages()[0].from).toBe('agent');
  });
});
