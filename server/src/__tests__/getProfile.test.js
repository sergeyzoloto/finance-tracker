import supertest from 'supertest';
import bcryptjs from 'bcryptjs';

import {
  connectToMockDB,
  closeMockDatabase,
  clearMockDatabase,
} from '../__testUtils__/dbMock.js';
import {
  addUserToMockDB,
  findUserInMockDB,
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

beforeEach(async () => {
  await addUserToMockDB(testUserBase);
});

describe('GET /api/user/profile', () => {
  it('Should return a bad request if no token is given', (done) => {
    request
      .get('/api/user/profile')
      .set('Cookie', [`token=`])
      .then((response) => {
        expect(response.status).toBe(499);

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

  it('Should return a bad request if invalid token is given', (done) => {
    const invalidToken = 'foo bar';

    request
      .get('/api/user/profile')
      .set('Cookie', [`token=${invalidToken}`])
      .send()
      .then((response) => {
        expect(response.status).toBe(498);

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

  it('Should return a success state if a correct token is given', async () => {
    const testUser = { ...testUserBase };

    // First log in to request token
    return (
      request
        .post('/api/user/login')
        .send({ user: testUser })
        .then((responseWithToken) => {
          expect(responseWithToken.status).toBe(200);

          const cookies = responseWithToken.headers['set-cookie'];
          const tokenCookie = cookies[0];

          // Extract the token value from the cookie
          const extractedToken = tokenCookie.split(';')[0].split('token=')[1];

          return extractedToken;
        })

        // Use token in our test
        .then(async (token) => {
          return request
            .get('/api/user/profile')
            .set('Cookie', [`token=${token}`])
            .send()
            .then((response) => {
              expect(response.status).toBe(200);

              const { id } = response.body.user;
              expect(response.body.user).toHaveProperty('id');

              const user = findUserInMockDB(id);
              return user;
            })
            .then((userInDb) => {
              expect(userInDb.email).toEqual(testUser.email);

              const passwordCheck = bcryptjs.compareSync(
                testUser.password,
                userInDb.password,
              );

              expect(passwordCheck).toBe(true);
            });
        })
    );
  });
});
