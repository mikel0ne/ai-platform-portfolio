import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AuthService } from './auth.service';
import { authInterceptor } from './auth.interceptor';

describe('authInterceptor', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;
  let auth: AuthService;

  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
  });

  afterEach(() => {
    httpMock.verify();
    sessionStorage.clear();
  });

  it('añade X-User-Id cuando hay usuario y la URL es /api/*', () => {
    auth.login({ id: 'u-1', name: 'Ana' });
    http.get('/api/copilot/users').subscribe();
    const req = httpMock.expectOne('/api/copilot/users');
    expect(req.request.headers.get('X-User-Id')).toBe('u-1');
    req.flush([]);
  });

  it('no añade cabecera si no hay usuario logueado', () => {
    http.get('/api/copilot/users').subscribe();
    const req = httpMock.expectOne('/api/copilot/users');
    expect(req.request.headers.has('X-User-Id')).toBeFalse();
    req.flush([]);
  });

  it('no añade cabecera a URLs ajenas a /api/', () => {
    auth.login({ id: 'u-1', name: 'Ana' });
    http.get('/assets/foo.json').subscribe();
    const req = httpMock.expectOne('/assets/foo.json');
    expect(req.request.headers.has('X-User-Id')).toBeFalse();
    req.flush({});
  });
});
