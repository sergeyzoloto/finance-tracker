import supertest from 'supertest';

import jwt from 'jsonwebtoken';

import {
  connectToMockDB,
  closeMockDatabase,
  clearMockDatabase,
} from '../__testUtils__/dbMock.js';
import app from '../app.js';
import {
  findUserInMockDB,
  addUserToMockDB,
} from '../__testUtils__/userMocks.js';

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

const testUserBase = { email: 'john@doe.com', password: 'qwerty123456' };

beforeEach(async () => {
  await addUserToMockDB(testUserBase);
});

describe('POST /api/user/login', () => {
  it('Should return a bad request if no user object is given', (done) => {
    request
      .post('/api/user/login')
      .then((response) => {
        expect(response.status).toBe(400);

        const { body } = response;
        expect(body.success).toBe(false);
        // Check that there is an error message
        expect(body.message.length).not.toBe(0);

        done();
      })
      .catch((error) => {
        done(error);
      });
  });

  it('Should return a bad request if the user object does not have a password', (done) => {
    const testUser = { email: testUserBase.email };

    request
      .post('/api/user/login')
      .send({ user: testUser })
      .then((response) => {
        expect(response.status).toBe(400);

        const { body } = response;
        expect(body.success).toBe(false);
        // Check that there is an error message
        expect(body.message.length).not.toBe(0);

        done();
      })
      .catch((error) => {
        done(error);
      });
  });

  it('Should return a bad request if the user object does not have an email', (done) => {
    const testUser = { password: testUserBase.password };

    request
      .post('/api/user/login')
      .send({ user: testUser })
      .then((response) => {
        expect(response.status).toBe(400);

        const { body } = response;
        expect(body.success).toBe(false);
        // Check that there is an error message
        expect(body.message.length).not.toBe(0);

        done();
      })
      .catch((error) => {
        done(error);
      });
  });

  it('Should return a bad request if the user object has extra fields', (done) => {
    const testUser = { ...testUserBase, foo: 'bar' };

    request
      .post('/api/user/login')
      .send({ user: testUser })
      .then((response) => {
        expect(response.status).toBe(400);

        const { body } = response;
        expect(body.success).toBe(false);
        // Check that there is an error message
        expect(body.message.length).not.toBe(0);

        done();
      })
      .catch((error) => {
        done(error);
      });
  });

  it('Should return a bad request if the user is not found', (done) => {
    const testUser = {
      password: testUserBase.password,
      email: 'wrong@email.com',
    };

    request
      .post('/api/user/login')
      .send({ user: testUser })
      .then((response) => {
        expect(response.status).toBe(404);

        const { body } = response;
        expect(body.success).toBe(false);
        // Check that there is an error message
        expect(body.message.length).not.toBe(0);

        done();
      })
      .catch((error) => {
        done(error);
      });
  });

  it('Should return a bad request if the password is wrong', (done) => {
    const testUser = { password: 'wrong', email: testUserBase.email };

    request
      .post('/api/user/login')
      .send({ user: testUser })
      .then((response) => {
        expect(response.status).toBe(400);

        const { body } = response;
        expect(body.success).toBe(false);
        // Check that there is an error message
        expect(body.message.length).not.toBe(0);

        done();
      })
      .catch((error) => {
        done(error);
      });
  });

  it('Should return a success state if correct user credentials are given', async () => {
    const testUser = { ...testUserBase };

    return request
      .post('/api/user/login')
      .send({ user: testUser })
      .then((response) => {
        expect(response.status).toBe(200);

        const { body } = response;
        expect(body.success).toBe(true);

        const cookies = response.headers['set-cookie'];
        expect(cookies).toHaveLength(1); // Assuming only one cookie is set

        const tokenCookie = cookies[0];
        expect(tokenCookie).toContain('token=');

        // Extract the token value from the cookie
        const extractedToken = tokenCookie.split(';')[0].split('token=')[1];
        const decodedUser = jwt.decode(extractedToken);
        const { id } = decodedUser;

        // Check that it matches added to the DB
        return findUserInMockDB(id);
      })
      .then((userInDb) => {
        expect(userInDb.email).toEqual(testUser.email);

        const passwordCheck = bcryptjs.compareSync(
          testUser.password,
          userInDb.password,
        );

        expect(passwordCheck).toBe(true);
      });
  });
});
