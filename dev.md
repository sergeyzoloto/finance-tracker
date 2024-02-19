# dev diary

[29.12.23] Created a new project. Prepared initial structure, incl. basic dependencies

[30.12.23] Installed and configured Tailwind. Performed customization and wrote the first simple test for the React Vite page

[31.12.23] Added a custom hook for backend requests. While making tests for it, found out that 'render' from @testing-library/react-hooks is no longer supported in React 18. Instead, a renderHook has been added to @testing-library/react that does not have the waitForNextUpdate property. It is suggested to either use an earlier version of React or, for example, use Enzyme
<https://testing-library.com/docs/react-testing-library/api/#renderhook>
<https://github.com/testing-library/react-testing-library/pull/991>

[01.01.24] Reworked the tests for the custom hook useFetch by adding the helper function waitFor instead of the similar waitForNextUpdate. The console warns about actions performed on the tested component outside act(...), although from my point of view the code causing changes is actually wrapped exactly in act(...).
<https://www.toptal.com/react/testing-react-hooks-tutorial>

[02.01.24] User model added

[04.01.24] User router and controller added. mongodb-memory-server and supertest are used to mock db and test the server side

[09.01.24] Simple home page added with tests template

[10.01.24] User list and create user input added

[12.01.24] Reorganized the general layout, added styling to the user creation form, fixed tests for the frontend

[16.01.24] Login and logout end points added and tested + Cookies management + Hashing passwords

[17.01.24] getProfile end point created and tested. login end point now response with cookies only

[22.01.24] Refactored the code to split password verification and token generation into separate functions for further use

[23.01.24] User mockDB functions added for testing purposes. Draft tests for updateUser function prepared. Update user profile function ready with tests. validateUser function is slightly modified to allow validation without password. updateUser function doesn't check password (token is enough), and updating password should be performed with a separate one

[24.01.24] User password update function added with tests

[25.01.24] Delete user function added with tests

[28.01.24] Rewrote the client tests using react-test-renderer, which solved the problem of the test component changing state outside act() in the useFetch.test.test.js test that occurred before. @testing-library/react-hooks was more obvious solution for testing, but unfortunately it is not compatible with the latest version of react (18).

[29.01.24] UserContext in client added. The tests check that userState is defined and setUserState modifies it.

[30.01.24] Added a LoginPage. Used 'history' package in testing to check redirection after successful login

[3.02.24] Some styling, small fixes. Tests
Now the server responds with the correct status and message when trying to create a user with an email that already exists in the database. The page CreateUser redirects to the UserList page in case of successful signup

[5.02.24] Project description updated and small changes applied

[7.02.24] Delete button and modal created with no tests (to-do)

[8.02.24] Prepared tests for the modal and credentials input components

[9.02.24] Made changes to the server controller user. The delete user function now deletes a user from the database based on the id in the request user object, not on the token in cookies

[14.02.24] Status rendering in the Modal component ready to test

When trying to attach a cookie token to the request and send a request to remove the user from the client, I get an error:

Access to fetch at '<http://localhost:5000/api/user/delete>' from origin '<http://localhost:5173>' has been blocked by CORS policy: Response to preflight request doesn't pass access control check: The value of the 'Access-Control-Allow-Origin' header in the response must not be the wildcard '*' when the request's credentials mode is 'include'.

This error is due to the CORS (Cross-Origin Resource Sharing) policy of the server. When making a fetch request with credentials: 'include', the server cannot respond with the Access-Control-Allow-Origin header set to *. It must be set to the exact origin of the request.

I need to configure the server to respond with the correct Access-Control-Allow-Origin header, like this:

const corsOptions = {
  origin: '<http://localhost:5173>', // This should be the client's origin
  credentials: true,
};

app.use(cors(corsOptions));

Parameterization will need to be adjusted depending on the environment (development or production)

[15.02.24] CORS options are configured on the server.
User deletion is tested manually from a browser
TO-DO: Prepare tests for user deletion

[16.02.24] Tests for UserList page interacting with DeleteUserModal

[19.02.24] Slightly refactored nav. Now content, styles and functionality are separated in code
