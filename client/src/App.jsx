import './App.css';
import React from 'react';
import { Routes, Route } from 'react-router-dom';
import Home from './pages/Home/Home';
import CreateUser from './pages/User/CreateUser';
import UserList from './pages/User/UserList';
import Layout from './layout/Layout';

function App() {
  return (
    <>
      <Routes>
        <Route path="/" element={<Layout />}>
          <Route index element={<Home />} />
          <Route path="/user" element={<UserList />} />
          <Route path="/user/create" element={<CreateUser />} />
        </Route>
      </Routes>
    </>
  );
}

export default App;
