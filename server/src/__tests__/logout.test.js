import supertest from 'supertest';

import {
  connectToMockDB,
  closeMockDatabase,
  clearMockDatabase,
} from '../__testUtils__/dbMock.js';
import app from '../app.js';
import { logInfo } from '../util/logging.js';

const request = supertest(app);

beforeAll(async () => {
  await connectToMockDB();
});

afterEach(async () => {
  await clearMockDatabase();
});

afterAll(async () => {
  await closeMockDatabase();
});

describe('GET /api/user/logout', () => {
  it('Always response with success and no cookies', async () => {
    request.get('/api/user/logout').then((response) => {
      expect(response.status).toBe(200);

      const { body } = response;
      expect(body.success).toBe(true);

      const cookies = response.headers['set-cookie'];
      expect(cookies[0]).toContain('token=;');
    });
  });
});
