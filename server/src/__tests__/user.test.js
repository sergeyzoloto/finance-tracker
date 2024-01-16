import supertest from 'supertest';

import {
  connectToMockDB,
  closeMockDatabase,
  clearMockDatabase,
} from '../__testUtils__/dbMock.js';
import { addUserToMockDB } from '../__testUtils__/userMocks.js';
import app from '../app.js';

// Hashing passwords
import bcryptjs from 'bcryptjs';

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

describe('GET /api/user/', () => {
  it('Should return an empty array if there are no users in the db', (done) => {
    request
      .get('/api/user/')
      .then((response) => {
        expect(response.status).toBe(200);

        const { body } = response;
        expect(body.success).toBe(true);
        expect(body.result).toEqual([]);

        done();
      })
      .catch((error) => {
        done(error);
      });
  });

  it('Should return all the users in the db', async () => {
    const testUser1 = { password: 'qwerty123456', email: 'john@doe.com' };
    const testUser2 = { password: 'qwerty654321', email: 'jane@doe.com' };

    await addUserToMockDB(testUser1);
    await addUserToMockDB(testUser2);

    // Asynchronous tests should return a Promise
    return request.get('/api/user/').then((response) => {
      expect(response.status).toBe(200);

      const { body } = response;
      expect(body.success).toBe(true);

      const users = body.result;
      expect(users).toHaveLength(2);

      const user1 = users.filter((user) => user.email === testUser1.email)[0];
      const user2 = users.filter((user) => user.email === testUser2.email)[0];

      const passwordCheck1 = bcryptjs.compareSync(
        testUser1.password,
        user1.password,
      );
      expect(passwordCheck1).toBe(true);

      const passwordCheck2 = bcryptjs.compareSync(
        testUser2.password,
        user2.password,
      );
      expect(passwordCheck2).toBe(true);

      expect(
        users.filter((user) => user.email === testUser1.email),
      ).toHaveLength(1);
      expect(
        users.filter((user) => user.email === testUser2.email),
      ).toHaveLength(1);
    });
  });
});
