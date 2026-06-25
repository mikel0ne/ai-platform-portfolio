import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { SearchStore } from './search-store';
import { SearchResponse } from '../core/models';

describe('SearchStore', () => {
  let store: SearchStore;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [SearchStore, provideHttpClient(), provideHttpClientTesting()],
    });
    store = TestBed.inject(SearchStore);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('posts the query and appends question + answer with citations', () => {
    store.search('  estándar de probes  ');

    // La pregunta del usuario aparece de inmediato y se marca como buscando.
    expect(store.messages().length).toBe(1);
    expect(store.messages()[0]).toEqual(jasmine.objectContaining({ from: 'user', text: 'estándar de probes' }));
    expect(store.searching()).toBeTrue();

    const req = httpMock.expectOne('/search');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ consulta: 'estándar de probes' });

    const body: SearchResponse = {
      respuesta: 'Las probes readiness deben…',
      citas: [{ fuente: 'estandar-microservicios.md', seccion: 'Probes', score: 0.83 }],
      confianza: 'alta',
    };
    req.flush(body);

    expect(store.searching()).toBeFalse();
    expect(store.messages().length).toBe(2);
    const answer = store.messages()[1];
    expect(answer.from).toBe('agent');
    expect(answer.text).toBe('Las probes readiness deben…');
    expect(answer.confianza).toBe('alta');
    expect(answer.citas?.[0].fuente).toBe('estandar-microservicios.md');
  });

  it('ignores empty queries', () => {
    store.search('   ');
    expect(store.messages().length).toBe(0);
    httpMock.expectNone('/search');
  });

  it('appends an error bubble when the request fails', () => {
    store.search('algo');
    httpMock.expectOne('/search').error(new ProgressEvent('network'));

    expect(store.searching()).toBeFalse();
    const last = store.messages()[store.messages().length - 1];
    expect(last.from).toBe('agent');
    expect(last.error).toBeTrue();
  });
});
