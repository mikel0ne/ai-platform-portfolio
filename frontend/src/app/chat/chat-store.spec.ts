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

  it('greet() añade un único mensaje del agente', () => {
    store.greet('Ana');
    expect(store.messages().length).toBe(1);
    expect(store.messages()[0].from).toBe('agent');
    expect(store.messages()[0].text).toContain('Ana');
  });

  it('send() añade el mensaje del usuario, bloquea el envío y añade la respuesta del agente', () => {
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

  it('send() en error muestra mensaje de respaldo y libera el bloqueo', () => {
    store.send('hola');
    const req = httpMock.expectOne('/api/copilot/chat');
    req.error(new ProgressEvent('error'), { status: 500, statusText: 'Server Error' });

    expect(store.sending()).toBeFalse();
    expect(store.messages().length).toBe(2);
    expect(store.messages()[1].from).toBe('agent');
    expect(store.messages()[1].text).toContain('no puedo atenderte');
  });

  it('send() ignora texto vacío', () => {
    store.send('   ');
    expect(store.messages().length).toBe(0);
    expect(store.sending()).toBeFalse();
    httpMock.expectNone('/api/copilot/chat');
  });

  it('newConversation() regenera el threadId y limpia el historial salvo el saludo', () => {
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
