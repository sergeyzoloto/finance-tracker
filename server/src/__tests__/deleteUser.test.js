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

describe('DELETE /api/user/delete', () => {
  it('Should return a bad request if no token is given', async () => {
    const testUser = { ...testUserBase };

    await addUserToMockDB(testUserBase);

    return request
      .delete('/api/user/delete')
      .set('Cookie', [`token=`])
      .send({ user: testUser })
      .then((response) => {
        expect(response.status).toBe(499);

        const { body } = response;
        expect(body.success).toBe(false);
        // Check that there is an error message
        expect(body.message.length).not.toBe(0);
      });
  });

  it('Should return a bad request if invalid token is given', async () => {
    await addUserToMockDB(testUserBase);

    const testUser = { ...testUserBase };
    const invalidToken = 'foo bar';

    return request
      .delete('/api/user/delete')
      .set('Cookie', [`token=${invalidToken}`])
      .send({ user: testUser })
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
      .delete('/api/user/delete')
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
      .delete('/api/user/delete')
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
      .delete('/api/user/delete')
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

  it('Should return a bad request if the password in request is not valid', async () => {
    const userId = await addUserToMockDB(testUserBase);
    const token = await generateTokenInMockDB(userId);

    const testUser = { ...testUserBase, password: 'foo' };

    return request
      .delete('/api/user/delete')
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
    const actingUserId = await addUserToMockDB({
      email: 'actor@test.com',
      password: 'qwerty123456',
    });
    const token = await generateTokenInMockDB(actingUserId);

    const userId = await addUserToMockDB(testUserBase);

    const deleteUser = { id: userId, password: testUserBase.password };

    return request
      .delete('/api/user/delete')
      .set('Cookie', [`token=${token}`])
      .send({ user: deleteUser })
      .then(async (response) => {
        expect(response.status).toBe(200);

        const { body } = response;
        expect(body.success).toBe(true);

        const userInResponse = body.user;

        expect(userInResponse.id).toBe(userId);
        const userInDB = await findUserInMockDB(userId);

        expect(userInDB).toBeNull();
      });
  });
});
