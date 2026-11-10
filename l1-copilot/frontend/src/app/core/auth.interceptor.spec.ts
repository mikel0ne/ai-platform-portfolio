import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors, withXhr } from '@angular/common/http';
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
        provideHttpClient(withXhr(), withInterceptors([authInterceptor])),
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

  it('adds X-User-Id when a user is logged in and the URL is /api/*', () => {
    auth.login({ id: 'u-1', name: 'Ana', age: 28, formal: false });
    http.get('/api/copilot/users').subscribe();
    const req = httpMock.expectOne('/api/copilot/users');
    expect(req.request.headers.get('X-User-Id')).toBe('u-1');
    req.flush([]);
  });

  it('adds no header without a logged-in user', () => {
    http.get('/api/copilot/users').subscribe();
    const req = httpMock.expectOne('/api/copilot/users');
    expect(req.request.headers.has('X-User-Id')).toBeFalse();
    req.flush([]);
  });

  it('adds no header to URLs outside /api/', () => {
    auth.login({ id: 'u-1', name: 'Ana', age: 28, formal: false });
    http.get('/assets/foo.json').subscribe();
    const req = httpMock.expectOne('/assets/foo.json');
    expect(req.request.headers.has('X-User-Id')).toBeFalse();
    req.flush({});
  });
});
