import './App.css';
import React from 'react';
import { Routes, Route } from 'react-router-dom';
import Home from './pages/Home/Home';
import CreateUser from './pages/User/CreateUser';
import UserList from './pages/User/UserList';
import Nav from './components/Nav';

function App() {
  return (
    <>
      <Nav />
      <Routes>
        <Route path="/" element={<Home />} />
        <Route path="/user" element={<UserList />} />
        <Route path="/user/create" element={<CreateUser />} />
      </Routes>
    </>
  );
}

export default App;
