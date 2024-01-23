import supertest from 'supertest';

import {
  connectToMockDB,
  closeMockDatabase,
  clearMockDatabase,
} from '../__testUtils__/dbMock.js';

import {
  addUserToMockDB,
  findUserInMockDB,
  generateTokenInMockDB,
} from '../__testUtils__/userMocks.js';

import bcryptjs from 'bcryptjs';

import app from '../app.js';

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

const testUserBase = { email: 'john@doe.com', password: 'qwerty123456' };

describe('PUT /api/user/update', () => {
  it('Should return a bad request if no token is given', async () => {
    return request
      .put('/api/user/update')
      .set('Cookie', [`token=`])
      .then((response) => {
        expect(response.status).toBe(499);

        const { body } = response;
        expect(body.success).toBe(false);
        // Check that there is an error message
        expect(body.message.length).not.toBe(0);
      });
  });

  it('Should return a bad request if invalid token is given', async () => {
    const invalidToken = 'foo bar';

    return request
      .put('/api/user/update')
      .set('Cookie', [`token=${invalidToken}`])
      .then((response) => {
        expect(response.status).toBe(498);

        const { body } = response;
        expect(body.success).toBe(false);
        // Check that there is an error message
        expect(body.message.length).not.toBe(0);
      });
  });

  it('Should return a bad request if there is no user object attached to body', async () => {
    const userId = await addUserToMockDB(testUserBase);
    const token = await generateTokenInMockDB(userId);

    return request
      .put('/api/user/update')
      .set('Cookie', [`token=${token}`])
      .send() // no user object
      .then((response) => {
        expect(response.status).toBe(400);

        const { body } = response;
        expect(body.success).toBe(false);
        // Check that there is an error message
        expect(body.message.length).not.toBe(0);
      });
  });

  it('Should return a bad request if there are no requested fields in user object', async () => {
    const userId = await addUserToMockDB(testUserBase);
    const token = await generateTokenInMockDB(userId);

    const testUser = {};

    return request
      .put('/api/user/update')
      .set('Cookie', [`token=${token}`])
      .send({ user: testUser })
      .then((response) => {
        expect(response.status).toBe(400);

        const { body } = response;
        expect(body.success).toBe(false);
        // Check that there is an error message
        expect(body.message.length).not.toBe(0);
      });
  });

  it('Should return a bad request if there are extra fields in user object', async () => {
    const userId = await addUserToMockDB(testUserBase);
    const token = await generateTokenInMockDB(userId);

    const testUser = { ...testUserBase, foo: 'bar' };

    return request
      .put('/api/user/update')
      .set('Cookie', [`token=${token}`])
      .send({ user: testUser })
      .then((response) => {
        expect(response.status).toBe(400);

        const { body } = response;
        expect(body.success).toBe(false);
        // Check that there is an error message
        expect(body.message.length).not.toBe(0);
      });
  });

  it('Should return a bad request in case of wrong fields in user object', async () => {
    const userId = await addUserToMockDB(testUserBase);
    const token = await generateTokenInMockDB(userId);

    const testUser = { ...testUserBase, foo: 'bar' };

    return request
      .put('/api/user/update')
      .set('Cookie', [`token=${token}`])
      .send({ user: testUser })
      .then((response) => {
        expect(response.status).toBe(400);

        const { body } = response;
        expect(body.success).toBe(false);
        // Check that there is an error message
        expect(body.message.length).not.toBe(0);
      });
  });

  it('Should return a success state if a correct token and user object are given', async () => {
    const userId = await addUserToMockDB(testUserBase);
    const token = await generateTokenInMockDB(userId);

    const testUser = { email: 'new@email.com' };

    return request
      .put('/api/user/update')
      .set('Cookie', [`token=${token}`])
      .send({ user: testUser })
      .then((response) => {
        expect(response.status).toBe(200);

        const { body } = response;
        expect(body.success).toBe(true);

        const userInResponse = body.user;

        expect(userInResponse.email).toEqual(testUser.email);
        expect(userInResponse._id).toBe(userId);

        return findUserInMockDB(userId);
      })
      .then((userInMockDb) => {
        expect(userInMockDb.email).toEqual(testUser.email);
        expect(userInMockDb._id.toString()).toEqual(userId);
      });
  });
});
