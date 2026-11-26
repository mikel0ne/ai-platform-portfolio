import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
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
        provideRouter([]),
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

  it('adds the bearer token when a user is logged in and the URL is /api/*', () => {
    auth.login({ id: 'u-1', name: 'Ana', age: 28, formal: false }, 'signed.token');
    http.get('/api/copilot/users').subscribe();
    const req = httpMock.expectOne('/api/copilot/users');
    expect(req.request.headers.get('Authorization')).toBe('Bearer signed.token');
    expect(req.request.headers.has('X-User-Id')).toBeFalse();
    req.flush([]);
  });

  it('adds no header without a logged-in user', () => {
    http.get('/api/copilot/users').subscribe();
    const req = httpMock.expectOne('/api/copilot/users');
    expect(req.request.headers.has('Authorization')).toBeFalse();
    req.flush([]);
  });

  it('adds no header to URLs outside /api/', () => {
    auth.login({ id: 'u-1', name: 'Ana', age: 28, formal: false }, 'signed.token');
    http.get('/assets/foo.json').subscribe();
    const req = httpMock.expectOne('/assets/foo.json');
    expect(req.request.headers.has('Authorization')).toBeFalse();
    req.flush({});
  });

  it('does not send the token to the login endpoint', () => {
    auth.login({ id: 'u-1', name: 'Ana', age: 28, formal: false }, 'signed.token');
    http.post('/api/copilot/login', { userId: 'u-2' }).subscribe();
    const req = httpMock.expectOne('/api/copilot/login');
    expect(req.request.headers.has('Authorization')).toBeFalse();
    req.flush({ token: 'x' });
  });

  it('clears the session and goes back to login on a 401 from the API', () => {
    const navigate = spyOn(TestBed.inject(Router), 'navigateByUrl').and.resolveTo(true);
    auth.login({ id: 'u-1', name: 'Ana', age: 28, formal: false }, 'signed.token');
    let status = 0;
    http.get('/api/copilot/tickets/1').subscribe({ error: e => (status = e.status) });
    httpMock.expectOne('/api/copilot/tickets/1').flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(status).toBe(401);
    expect(auth.user()).toBeNull();
    expect(auth.token()).toBeNull();
    expect(navigate).toHaveBeenCalledWith('/');
  });

  it('keeps the session on other errors', () => {
    auth.login({ id: 'u-1', name: 'Ana', age: 28, formal: false }, 'signed.token');
    http.get('/api/copilot/tickets/1').subscribe({ error: () => undefined });
    httpMock.expectOne('/api/copilot/tickets/1').flush({}, { status: 503, statusText: 'Unavailable' });
    expect(auth.user()).not.toBeNull();
  });
});
